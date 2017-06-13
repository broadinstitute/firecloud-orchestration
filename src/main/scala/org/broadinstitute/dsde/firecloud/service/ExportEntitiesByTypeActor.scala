package org.broadinstitute.dsde.firecloud.service

import java.util.Date

import akka.actor.{Actor, ActorContext, ActorRef, ActorRefFactory, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import better.files.File
import com.google.api.services.storage.model.StorageObject
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo}
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.ExportEntities
import org.broadinstitute.dsde.firecloud.service.TSVWriterActor._
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException}
import org.broadinstitute.dsde.rawls.model._
import org.slf4j.{Logger, LoggerFactory}
import spray.http.ContentTypes
import spray.routing.RequestContext

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Success

object ExportEntitiesByTypeActor {
  sealed trait ExportEntitiesByTypeMessage
  case class ExportEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]) extends ExportEntitiesByTypeMessage

  def props(exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor, userInfo: UserInfo): Props = {
    Props(exportEntitiesByTypeConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new ExportEntitiesByTypeActor(app.rawlsDAO, app.googleServicesDAO, userInfo)
}

class ExportEntitiesByTypeActor(val rawlsDAO: RawlsDAO, val googleDAO: GoogleServicesDAO, val userInfo: UserInfo)(implicit protected val executionContext: ExecutionContext) extends Actor with ExportEntitiesByType {
  // ExportEntities requires its own actor context to work with TsvWriterActor
  def actorRefFactory: ActorContext = context
  override def receive: Receive = {
    case ExportEntities(ctx, workspaceNamespace, workspaceName, filename, entityType, attributeNames) => exportEntities(ctx, workspaceNamespace, workspaceName, filename, entityType, attributeNames) pipeTo sender
  }
}

trait ExportEntitiesByType extends FireCloudRequestBuilding {
  val rawlsDAO: RawlsDAO
  val googleDAO: GoogleServicesDAO
  implicit val userInfo: UserInfo
  implicit protected val executionContext: ExecutionContext
  implicit def actorRefFactory: ActorRefFactory
  implicit val timeout = Timeout(10 minute)

  private val downloadSizeThreshold: Int = 50000
  private val groupedByteSize: Int = 1024 * 1024

  lazy val log: Logger = LoggerFactory.getLogger(getClass)

  /*
   * Approach:
   *   1. Find the entity metadata
   *   2. If the data is relatively small and can confidently be downloaded without hitting timeouts, then stream content directly
   *   3. Otherwise, upload content to the workspace's GCS bucket and send the user a signed URL to the content.
   *   4. In both cases, use the TSV Writing Actor to keep the # entities in-memory low and not trigger OOM errors.
   *
   *   TODO:
   *
   *   * Error handling needs a lot of work.
   *
   *   * Need to figure out exactly what the signed url actually looks like. Is it a string? A text file with an explanation?
   *
   *   * Tune the download/GCS decision based on # entities times # attributes. Is ~50K a good number?
   *
   *   * ??? Move the streaming actor here so the calling APIs don't have to worry about it.
   *
   *   * Need to somehow tell the caller about a different filename.
   *      Setting that at the caller level doesn't make sense when the result of a set download could be either a text or zip.
   */
  def exportEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, fileName: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[Stream[Array[Byte]]] = {

    // Get entity metadata: count and full list of attributes
    getEntityTypeMetadata(workspaceNamespace, workspaceName, entityType) flatMap { metadata =>
      // Generate all of the paginated queries to find all of the entities
      val entityQueries = getEntityQueries(metadata, entityType)
      val file = bufferEntitiesToFile(metadata, attributeNames, entityQueries, workspaceNamespace, workspaceName, entityType)

      metadata.count * metadata.attributeNames.size match {
        case x if x > downloadSizeThreshold =>
          val workspaceResponse = rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)
          workspaceResponse.map { workspaceResponse =>
            val bucketName = workspaceResponse.workspace.bucketName
            // Prefix with timestamp to prevent overwriting previous uploads
            val newFileName = new Date().getTime + "_" + fileName
            file map { f =>
              val renamedFile = f.renameTo(newFileName)
              sendFileToGCS(userInfo, bucketName, renamedFile)
            }
            getSignedUrlContent(entityType, bucketName, newFileName).getBytes.grouped(groupedByteSize).toStream
          }
        case _ =>
          // File.bytes is an Iterator[Byte]. Convert to a 1M byte array stream to limit what's in memory
          file.map(_.bytes.grouped(groupedByteSize).map(_.toArray).toStream)
      }
    } recoverWith {
      case t: Throwable => throw new FireCloudException("Unable to generate download file content", t)
    }
  }

  private def getSignedUrlContent(entityType: String, bucketName: String, fileName: String): String = {
    val url = googleDAO.getObjectResourceUrl(bucketName, fileName)
    s"Please visit the following URL: $url to download your $entityType data"
  }

  private def sendFileToGCS(userInfo: UserInfo, bucketName: String, file: File): StorageObject = {
    googleDAO.writeBucketObjectFromFile(userInfo, bucketName, file.contentType.getOrElse(ContentTypes.`text/plain`.toString), file.name, file.toJava)
  }

  // We batch entities to a temp file(s) to minimize memory use
  private def bufferEntitiesToFile(metadata: EntityTypeMetadata, attributeNames: Option[IndexedSeq[String]], entityQueries: Seq[EntityQuery], workspaceNamespace: String, workspaceName: String, entityType: String): Future[File] = {
    // batch queries into groups so we don't drop a bunch of hot futures into the stack
    val maxConnections = ConfigFactory.load().getInt("spray.can.host-connector.max-connections")
    val groupedQueries = entityQueries.grouped(maxConnections).toSeq
    // Always need an entity writer, sometimes need a membership writer.
    val entityWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, entityQueries.size))
    val membershipWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, entityQueries.size))

    // Collection Types require two files (entity and membership) zipped together.
    val fileWritingOperation = ModelSchema.isCollectionType(entityType) match {
      case Success(x) if x =>
        // Fold over the groups, collect entities for each group, write entities to file(s), collect file responses
        val foldOperation = groupedQueries.foldLeft(Future.successful(0, Seq[File]())) { (accumulator, queryGroup) =>
          for {
            acc <- accumulator
            entityBatch <- getEntityBatchFromQueries(queryGroup, workspaceNamespace, workspaceName, entityType)
            membershipTSV <- (membershipWriter ? WriteMembershipTSV(acc._1, entityBatch)).mapTo[File]
            entityTSV <- (entityWriter ? WriteEntityTSV(acc._1, entityBatch)).mapTo[File]
            zip <- writeFilesToZip(entityType, membershipTSV, entityTSV)
          } yield (acc._1 + 1, Seq(zip))
        }
        foldOperation map { files => files._2.head }
      case _ =>
        // Fold over the groups, collect entities for each group, write entities to file, collect file results
        val foldOperation = groupedQueries.foldLeft(Future.successful((0, Seq[File]()))) { (accumulator, queryGroup) =>
          for {
            acc <- accumulator
            entityBatch <- getEntityBatchFromQueries(queryGroup, workspaceNamespace, workspaceName, entityType)
            entityTSV <- (entityWriter ? WriteEntityTSV(acc._1, entityBatch)).mapTo[File]
          } yield (acc._1 + 1, Seq(entityTSV))
        }
        foldOperation map { files => files._2.head }
    }
    fileWritingOperation.recoverWith {
      case t: Throwable => throw new FireCloudException("Unable to generate download file content", t)
    }
  }

  private def writeFilesToZip(entityType: String, membershipTSV: File, entityTSV: File): Future[File] = {
    Future {
      val zipFile = File.newTemporaryDirectory()
      val membership = zipFile/s"${entityType}_membership.tsv"
      val entity = zipFile/s"${entityType}_entity.tsv"
      membershipTSV.copyTo(membership)
      entityTSV.copyTo(entity)
      zipFile.zip()
    }
  }

  private def getEntityBatchFromQueries(queryGroup: Seq[EntityQuery], workspaceNamespace: String, workspaceName: String, entityType: String): Future[Seq[Entity]] = {
    Future.sequence(
      queryGroup map { query =>
        getEntities(workspaceNamespace, workspaceName, entityType, query)
      }
    ) map(_.flatten)
  }

  private def getEntityQueries(metadata: EntityTypeMetadata, entityType: String): Seq[EntityQuery] = {
    val pageSize = FireCloudConfig.Rawls.defaultPageSize
    val filteredCount = metadata.count
    val sortField = entityType + "_id"
    val pages = filteredCount % pageSize match {
      case x if x == 0 => filteredCount / pageSize
      case x => filteredCount / pageSize + 1
    }
    (1 to pages) map { page =>
      EntityQuery(page = page, pageSize = pageSize, sortField = sortField, sortDirection = SortDirections.Ascending, filterTerms = None)
    }
  }

  private def getEntityTypeMetadata(workspaceNamespace: String, workspaceName: String, entityType: String): Future[EntityTypeMetadata] = {
    rawlsDAO.getEntityTypes(workspaceNamespace, workspaceName) map { metadata =>
      metadata.get(entityType)
    } map {
      case Some(m) => m
      case _ => throw new FireCloudException(s"Unable to collect entity metadata for $workspaceNamespace:$workspaceName:$entityType")
    }
  }

  private def getEntities(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery): Future[Seq[Entity]] = {
    rawlsDAO.queryEntitiesOfType(workspaceNamespace, workspaceName, entityType, query) map {
      response => response.results
    }
  }

}
