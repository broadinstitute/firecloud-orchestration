package org.broadinstitute.dsde.firecloud.service

import akka.Done
import akka.actor.{Actor, ActorContext, ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.Timeout
import better.files.File
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.{UserInfo, _}
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.ExportEntities
import org.broadinstitute.dsde.firecloud.service.TSVWriterActor._
import org.broadinstitute.dsde.firecloud.utils.StreamingActor.{ChunkEnd, FirstChunk, NextChunk}
import org.broadinstitute.dsde.firecloud.utils.{StreamingActor, TSVFormatter}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model._
import org.slf4j.{Logger, LoggerFactory}
import spray.http.{ContentTypes, _}
import spray.routing.RequestContext

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

// JSON Serialization Support
import spray.json._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._


object ExportEntitiesByTypeActor {
  sealed trait ExportEntitiesByTypeMessage
  case class ExportEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, entityType: String, attributeNames: Option[IndexedSeq[String]]) extends ExportEntitiesByTypeMessage

  def props(exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor, userInfo: UserInfo): Props = {
    Props(exportEntitiesByTypeConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new ExportEntitiesByTypeActor(app.rawlsDAO, app.googleServicesDAO, userInfo)
}

class ExportEntitiesByTypeActor(val rawlsDAO: RawlsDAO, val googleDAO: GoogleServicesDAO, val userInfo: UserInfo)(implicit protected val executionContext: ExecutionContext) extends Actor with ExportEntitiesByType {
  // Requires its own actor context to work with downstream actors: TSVWriterActor and StreamingActor
  def actorRefFactory: ActorContext = context
  override def receive: Receive = {
    case ExportEntities(ctx, workspaceNamespace, workspaceName, entityType, attributeNames) => streamEntities(ctx, workspaceNamespace, workspaceName, entityType, attributeNames) pipeTo sender
  }
}

trait ExportEntitiesByType extends FireCloudRequestBuilding {
  val rawlsDAO: RawlsDAO
  val googleDAO: GoogleServicesDAO
  implicit val userInfo: UserInfo
  implicit protected val executionContext: ExecutionContext
  implicit def actorRefFactory: ActorRefFactory

  implicit val timeout = Timeout(1 minute)

  lazy val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
    * General Approach
    * 1. Define a `Source` of entity queries
    * 2. Run the source events through a `Flow`.
    * 3. Flow sends events (batch of entities) to a streaming output actor
    * 4. Return a Done to the calling route when complete.
    *
    * TODO: Could this be per-requested???
    */
  def streamEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[Any] = {

    // Akka Streams Support
    implicit val system = ActorSystem("Streaming-Entity-Exporter")
    implicit val materializer = ActorMaterializer()

    getEntityTypeMetadata(workspaceNamespace, workspaceName, entityType) flatMap { metadata =>
      val entityQueries = getEntityQueries(metadata, entityType)
      val headers = TSVFormatter.makeEntityHeaders(entityType, metadata.attributeNames, attributeNames)

      // TODO: Could the collection type be modeled as a source, piped to two flows, and then merged into a zip?
      if (TSVFormatter.isCollectionType(entityType)) {
        logger.debug("This is a set ... need to query the content to a zip file.")
        // The output file
        val zipFile = writeCollectionTypeZipFile(workspaceNamespace, workspaceName, entityType, entityQueries, metadata, attributeNames)
        // The output to the user
        val streamingActorRef = actorRefFactory.actorOf(Props(new StreamingActor(ctx, ContentTypes.`application/octet-stream`, entityType + ".zip")))
        zipFile map { f =>
          streamingActorRef ! FirstChunk(HttpData.apply(f.byteArray))
          streamingActorRef ! ChunkEnd
        } map(r => Done)
      } else {
        // The output to the user
        val streamingActorRef = actorRefFactory.actorOf(Props(new StreamingActor(ctx, ContentTypes.`text/plain`, entityType + ".txt")))
        // The Source
        val entityQuerySource = Source(entityQueries.toStream)
        // Map over the source with transformations
        entityQuerySource.mapAsync(4) { query =>
          logger.info(s"Iterating over query: ${query.toString}")
          val entityOutput = getEntities(workspaceNamespace, workspaceName, entityType, query) map { entities =>
            sendRowsAsChunks(streamingActorRef, query, entityQueries.size, entityType, headers, entities)
          }
          entityOutput map { o => Future.successful("Done") }
        }.runWith(Sink.ignore)
      }
    }
  }.recoverWith {
    case fe: FireCloudExceptionWithErrorReport =>
      Future(ctx.complete(HttpResponse(
        status = fe.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError),
        entity = HttpEntity(ContentTypes.`application/json`, fe.errorReport.toJson.compactPrint))))
    case t: Throwable =>
      val errorReport = ErrorReport(StatusCodes.InternalServerError, "Error generating entity download: " + t.getMessage)
      Future(ctx.complete(HttpResponse(
        status = StatusCodes.InternalServerError,
        entity = HttpEntity(ContentTypes.`application/json`, errorReport.toJson.compactPrint))))
  }

  /*
   * Helper Methods
   */

  private def sendRowsAsChunks(actorRef: ActorRef, query: EntityQuery, querySize: Int, entityType: String, headers: IndexedSeq[String], entities: Seq[Entity]): Unit = {
    val rows = TSVFormatter.makeEntityRows(entityType, entities, headers)
    // Send headers if needed
    if (query.page == 1) { actorRef ! FirstChunk(HttpData(headers.mkString("\t") + "\n"))}
    // Send entities
    actorRef ! NextChunk(HttpData(rows.map { _.mkString("\t") }.mkString("\n") + "\n"))
    // Close the download if needed
    if (query.page == querySize) { actorRef ! ChunkEnd}
  }

  private def writeCollectionTypeZipFile(workspaceNamespace: String, workspaceName: String, entityType: String, entityQueries: Seq[EntityQuery], metadata: EntityTypeMetadata, attributeNames: Option[IndexedSeq[String]]): Future[File] = {
    val entityWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, entityQueries.size))
    val membershipWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, entityQueries.size))
    val foldOperation = entityQueries.foldLeft(Future.successful(0, Seq[File]())) { (accumulator, queryGroup) =>
      for {
        acc <- accumulator
        entityBatch <- getEntityBatchFromQueries(entityQueries, workspaceNamespace, workspaceName, entityType)
        membershipTSV <- (membershipWriter ? WriteMembershipTSV(acc._1, entityBatch)).mapTo[File]
        entityTSV <- (entityWriter ? WriteEntityTSV(acc._1, entityBatch)).mapTo[File]
        zip <- writeFilesToZip(entityType, membershipTSV, entityTSV)
      } yield (acc._1 + 1, Seq(zip))
    }
    foldOperation map { files => files._2.head }
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
    rawlsDAO.getEntityTypes(workspaceNamespace, workspaceName).
      map { metadata => metadata.get(entityType) }.
      map {
        case Some(m) => m
        case _ =>
          logger.error(s"Exception: Unable to collect entity metadata for $workspaceNamespace:$workspaceName:$entityType")
          throw new FireCloudExceptionWithErrorReport(ErrorReport(s"Unable to collect entity metadata for $workspaceNamespace:$workspaceName:$entityType"))
      }
  }

  private def getEntities(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery): Future[Seq[Entity]] = {
    rawlsDAO.queryEntitiesOfType(workspaceNamespace, workspaceName, entityType, query) map {
      response => response.results
    }
  }

}
