package org.broadinstitute.dsde.firecloud.service

import java.io
import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpCharsets, HttpEntity, HttpResponse, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.RequestContext
import akka.pattern.pipe
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.scaladsl.{Source => AkkaSource}

import scala.io.Source
import akka.http.scaladsl.model.HttpEntity.{ChunkStreamPart, Chunked}
import akka.http.scaladsl.model.headers.{Connection, ContentDispositionTypes, `Content-Disposition`}
import akka.http.scaladsl.model.{ContentTypes, HttpResponse}
import akka.util.{ByteString, Timeout}
import better.files.File
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{UserInfo, _}
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.ExportEntities
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model._
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

case class ExportEntitiesByTypeArguments (
                                           userInfo: UserInfo,
                                           workspaceNamespace: String,
                                           workspaceName: String,
                                           entityType: String,
                                           attributeNames: Option[IndexedSeq[String]],
                                           model: Option[String]
                                         )

object ExportEntitiesByTypeActor {

  sealed trait ExportEntitiesByTypeMessage
  case object ExportEntities extends ExportEntitiesByTypeMessage

  def constructor(app: Application, system: ActorSystem)(exportArgs: ExportEntitiesByTypeArguments)(implicit executionContext: ExecutionContext) =
    new ExportEntitiesByTypeActor(app.rawlsDAO, exportArgs.userInfo, exportArgs.workspaceNamespace,
      exportArgs.workspaceName, exportArgs.entityType, exportArgs.attributeNames, exportArgs.model, system)
}

/**
  * This class takes an akka.stream approach to generating download content. To facilitate sending
  * large amounts of paginated data to the client, we need to process the paginated content with a
  * limited memory footprint. In the case of singular entity downloads, we can also immediately begin
  * a content stream to the user to avoid browser timeouts. In the case of set entity downloads, we
  * can use efficient akka.stream techniques to generate files. Using a paginated approach resolves
  * timeouts between Orchestration and other services. Using akka.streams resolves both memory issues
  * and backpressure considerations between upstream producers and downstream consumers.
  */
class ExportEntitiesByTypeActor(rawlsDAO: RawlsDAO,
                                argUserInfo: UserInfo,
                                workspaceNamespace: String,
                                workspaceName: String,
                                entityType: String,
                                attributeNames: Option[IndexedSeq[String]],
                                model: Option[String],
                                argSystem: ActorSystem)
                               (implicit protected val executionContext: ExecutionContext) extends LazyLogging {

  implicit val timeout: Timeout = Timeout(1 minute)
  implicit val userInfo: UserInfo = argUserInfo
  implicit val system:ActorSystem = argSystem

  implicit val modelSchema: ModelSchema = model match {
    case Some(name) => ModelSchemaRegistry.getModelForSchemaType(SchemaTypes.withName(name))
    // if no model is specified, use the previous behavior - assume firecloud model
    case None => ModelSchemaRegistry.getModelForSchemaType(SchemaTypes.FIRECLOUD)
  }

  def ExportEntities = streamEntities()

  /**
    * Two basic code paths
    *
    * For Collection types, write the content to temp files, zip and return.
    *
    * For Singular types, pipe the content from `Source` -> `Flow` -> `Sink`
    * Source generates the entity queries
    * Flow executes the queries and sends formatted content to chunked response handler
    * Sink finishes the execution pipeline
    *
    * Handle exceptions directly by completing the request.
    */
  def streamEntities(): Future[HttpResponse] = {
    val keepAlive = Connection("Keep-Alive")

    entityTypeMetadata flatMap { metadata =>
      val entityQueries = getEntityQueries(metadata, entityType)
      if (modelSchema.isCollectionType(entityType)) {
        val contentType = ContentTypes.`application/octet-stream`
        val fileName = entityType + ".zip"
        val disposition = `Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> fileName))

        streamCollectionType(entityQueries, metadata).map { source =>
          HttpResponse(entity = HttpEntity.fromFile(contentType, source.toJava), headers = List(keepAlive, disposition))
        }
      } else {
        val headers = TSVFormatter.makeEntityHeaders(entityType, metadata.attributeNames, attributeNames)

        val contentType = ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
        val fileName = entityType + ".tsv"
        val disposition = `Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> fileName))

        streamSingularType(entityQueries, metadata, headers).map { source =>
          HttpResponse(entity = HttpEntity.fromFile(contentType, source.toJava), headers = List(keepAlive, disposition))
        }
      }
    }
  }.recoverWith {
    // Standard exceptions have to be handled as a completed request
    case t: Throwable => handleStandardException(t)
  }


  /*
   * Helper Methods
   */

  // Standard exceptions have to be handled as a completed request
  private def handleStandardException(t: Throwable): Future[HttpResponse] = {
    val errorReport = t match {
      case f: FireCloudExceptionWithErrorReport => f.errorReport
      case _ => ErrorReport(StatusCodes.InternalServerError, s"FireCloudException: Error generating entity download: ${t.getMessage}")
    }
    Future(HttpResponse(
      status = errorReport.statusCode.getOrElse(StatusCodes.InternalServerError),
      entity = HttpEntity(ContentTypes.`application/json`, errorReport.toJson.compactPrint)))
  }

  private def streamSingularType(entityQueries: Seq[EntityQuery], metadata: EntityTypeMetadata, entityHeaders: IndexedSeq[String]): Future[File] = {
    val tempEntityFile: File = File.newTemporaryFile(prefix = entityType)
    val entitySink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(tempEntityFile.path)

    // Run the Split Entity Flow that pipes entities through the two flows to the two file sinks
    // Result of this will be a tuple of Future[IOResult] that represents the success or failure of
    // streaming content to the file sinks.
    val fileStreamIOResults: Future[IOResult] = {
      RunnableGraph.fromGraph(GraphDSL.createGraph(entitySink) { implicit builder =>
        (eSink) =>
          import GraphDSL.Implicits._

          // Sources
          val querySource: Outlet[EntityQuery] = builder.add(AkkaSource(entityQueries.to(LazyList))).out
          val entityHeaderSource: Outlet[ByteString] = builder.add(AkkaSource.single(ByteString(entityHeaders.mkString("\t") + "\n"))).out

          // Flows
          val queryFlow: FlowShape[EntityQuery, Seq[Entity]] = builder.add(Flow[EntityQuery].mapAsync(1) { query => getEntitiesFromQuery(query) })
          val splitter: UniformFanOutShape[Seq[Entity], Seq[Entity]] = builder.add(Broadcast[Seq[Entity]](1))
          val entityFlow: FlowShape[Seq[Entity], ByteString] = builder.add(Flow[Seq[Entity]].map { entities =>
            val rows = TSVFormatter.makeEntityRows(entityType, entities, entityHeaders)
            ByteString(rows.map { _.mkString("\t")}.mkString("\n") + "\n")
          })
          val eConcat: UniformFanInShape[ByteString, ByteString] = builder.add(Concat[ByteString]())

          // Graph
          entityHeaderSource                                                 ~> eConcat
          querySource ~>  queryFlow ~> splitter ~> entityFlow     ~> eConcat ~> eSink
          ClosedShape
      }).run()
    }

    // Check that each file is completed
    val fileStreamResult = for {
      eResult <- fileStreamIOResults
    } yield eResult

    fileStreamResult map { _ =>
      tempEntityFile
    } recover {
      case _:Exception =>
        throw new FireCloudExceptionWithErrorReport(ErrorReport(s"FireCloudException: Unable to stream tsv file to user for $workspaceNamespace:$workspaceName:$entityType"))
    }
  }

  private def streamCollectionType(entityQueries: Seq[EntityQuery], metadata: EntityTypeMetadata): Future[File] = {

    // Two File sinks, one for each kind of entity set file needed.
    // The temp files will end up zipped and streamed when complete.
    val tempEntityFile: File = File.newTemporaryFile(prefix = "entity_")
    val entitySink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(tempEntityFile.path)
    val tempMembershipFile: File = File.newTemporaryFile(prefix = "membership_")
    val membershipSink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(tempMembershipFile.path)

    // Headers
    val entityHeaders: IndexedSeq[String] = TSVFormatter.makeEntityHeaders(entityType, metadata.attributeNames, attributeNames)
    val membershipHeaders: IndexedSeq[String] = TSVFormatter.makeMembershipHeaders(entityType)

    // Run the Split Entity Flow that pipes entities through the two flows to the two file sinks
    // Result of this will be a tuple of Future[IOResult] that represents the success or failure of
    // streaming content to the file sinks.
    val fileStreamIOResults: (Future[IOResult], Future[IOResult]) = {
      RunnableGraph.fromGraph(GraphDSL.createGraph(entitySink, membershipSink)((_, _)) { implicit builder =>
        (eSink, mSink) =>
          import GraphDSL.Implicits._

          // Sources
          val querySource: Outlet[EntityQuery] = builder.add(AkkaSource(entityQueries.to(LazyList))).out
          val entityHeaderSource: Outlet[ByteString] = builder.add(AkkaSource.single(ByteString(entityHeaders.mkString("\t") + "\n"))).out
          val membershipHeaderSource: Outlet[ByteString] = builder.add(AkkaSource.single(ByteString(membershipHeaders.mkString("\t") + "\n"))).out

          // Flows
          val queryFlow: FlowShape[EntityQuery, Seq[Entity]] = builder.add(Flow[EntityQuery].mapAsync(1) { query => getEntitiesFromQuery(query) })
          val splitter: UniformFanOutShape[Seq[Entity], Seq[Entity]] = builder.add(Broadcast[Seq[Entity]](2))
          val entityFlow: FlowShape[Seq[Entity], ByteString] = builder.add(Flow[Seq[Entity]].map { entities =>
            val rows = TSVFormatter.makeEntityRows(entityType, entities, entityHeaders)
            ByteString(rows.map { _.mkString("\t")}.mkString("\n") + "\n")
          })
          val membershipFlow: FlowShape[Seq[Entity], ByteString] = builder.add(Flow[Seq[Entity]].map { entities =>
            val rows = TSVFormatter.makeMembershipRows(entityType, entities)
            ByteString(rows.map { _.mkString("\t")}.mkString("\n") + "\n")
          })
          val eConcat: UniformFanInShape[ByteString, ByteString] = builder.add(Concat[ByteString]())
          val mConcat: UniformFanInShape[ByteString, ByteString] = builder.add(Concat[ByteString]())

          // Graph
          entityHeaderSource                                                 ~> eConcat
          querySource ~>  queryFlow ~> splitter ~> entityFlow     ~> eConcat ~> eSink
          membershipHeaderSource                                             ~> mConcat
          splitter ~> membershipFlow ~> mConcat ~> mSink
          ClosedShape
      }).run()
    }

    // Check that each file is completed
    val fileStreamResult = for {
      eResult <- fileStreamIOResults._1
      mResult <- fileStreamIOResults._2
    } yield ()

    // And then map those files to a ZIP.
    fileStreamResult flatMap { _ =>
      val zipFile: Future[File] = writeFilesToZip(tempEntityFile, tempMembershipFile)
      // The output to the user
      zipFile
    } recover {
      case _:Exception =>
        throw new FireCloudExceptionWithErrorReport(ErrorReport(s"FireCloudException: Unable to stream zip file to user for $workspaceNamespace:$workspaceName:$entityType"))
    }
  }

  private def writeFilesToZip(entityTSV: File, membershipTSV: File): Future[File] = {
    try {
      val zipFile = File.newTemporaryDirectory()
      membershipTSV.moveTo(zipFile/s"${entityType}_membership.tsv")
      entityTSV.moveTo(zipFile/s"${entityType}_entity.tsv")
      zipFile.zip()
      Future { zipFile.zip() }
    } catch {
      case t: Throwable => Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, s"FireCloudException: Unable to create zip file.", t)))
    }
  }

  private def getEntityQueries(metadata: EntityTypeMetadata, entityType: String): Seq[EntityQuery] = {
    val pageSize = FireCloudConfig.Rawls.defaultPageSize
    val filteredCount = metadata.count
    val sortField = "name" // Anything else and Rawls execution time blows up due to a join (GAWB-2350)
    val pages = Math.ceil(filteredCount.toDouble / pageSize.toDouble).toInt
    (1 to pages) map { page =>
      EntityQuery(page = page, pageSize = pageSize, sortField = sortField, sortDirection = SortDirections.Ascending, filterTerms = None)
    }
  }

  private def entityTypeMetadata: Future[EntityTypeMetadata] = {
    rawlsDAO.getEntityTypes(workspaceNamespace, workspaceName).
      map(_.getOrElse(entityType,
        throw new FireCloudExceptionWithErrorReport(ErrorReport(s"Unable to collect entity metadata for $workspaceNamespace:$workspaceName:$entityType")))
      )
  }

  private def getEntitiesFromQuery(query: EntityQuery): Future[Seq[Entity]] = {
    rawlsDAO.queryEntitiesOfType(workspaceNamespace, workspaceName, entityType, query) map {
      response => response.results
    }
  }

}
