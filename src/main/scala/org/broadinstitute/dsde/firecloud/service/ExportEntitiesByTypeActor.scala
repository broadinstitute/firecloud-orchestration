package org.broadinstitute.dsde.firecloud.service

import akka.Done
import akka.actor.{Actor, ActorContext, ActorRef, Props}
import akka.pattern.pipe
import akka.stream._
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}
import better.files.File
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{UserInfo, _}
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.ExportEntities
import org.broadinstitute.dsde.firecloud.utils.StreamingActor.{FirstChunk, NextChunk}
import org.broadinstitute.dsde.firecloud.utils.{StreamingActor, TSVFormatter}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model._
import spray.http.{ContentTypes, _}
import spray.json._
import spray.routing.RequestContext
import DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps


object ExportEntitiesByTypeActor {
  sealed trait ExportEntitiesByTypeMessage
  case class ExportEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, entityType: String, attributeNames: Option[IndexedSeq[String]]) extends ExportEntitiesByTypeMessage

  def props(exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor, userInfo: UserInfo): Props = {
    Props(exportEntitiesByTypeConstructor(userInfo))
  }

  def constructor(app: Application, materializer: ActorMaterializer)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new ExportEntitiesByTypeActor(app.rawlsDAO, userInfo, materializer)
}

class ExportEntitiesByTypeActor(val rawlsDAO: RawlsDAO, val argUserInfo: UserInfo, argMaterializer: ActorMaterializer)(implicit protected val executionContext: ExecutionContext) extends Actor with LazyLogging {

  // Requires its own actor context to work with downstream actors: TSVWriterActor and StreamingActor
  def actorRefFactory: ActorContext = context

  implicit val timeout: Timeout = Timeout(1 minute)
  implicit val userInfo: UserInfo = argUserInfo
  implicit val materializer: ActorMaterializer = argMaterializer

  override def receive: Receive = {
    case ExportEntities(ctx, workspaceNamespace, workspaceName, entityType, attributeNames) => streamEntities(ctx, workspaceNamespace, workspaceName, entityType, attributeNames) pipeTo sender
  }

  /**
    * Two basic code paths
    *
    * For Collection types, write the content to temp files, zip and return.
    *
    * For Singular types, pipe the content from `Source` -> `Flow` -> `Sink`
    *   Source generates the entity queries
    *   Flow executes the queries and sends formatted content to chunked response handler
    *   Sink finishes the execution pipeline
    *
    * Handle exceptions directly by completing the request.
    */
  def streamEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[Unit] = {
    getEntityTypeMetadata(workspaceNamespace, workspaceName, entityType) flatMap { metadata =>
      val entityQueries = getEntityQueries(metadata, entityType)

      // Stream exceptions have to be handled by directly closing out the RequestContext responder
      if (TSVFormatter.isCollectionType(entityType)) {
        streamCollectionType(ctx, workspaceNamespace, workspaceName, entityType, entityQueries, metadata, attributeNames).onFailure {
          case t: Throwable => handleStreamException(ctx, t)
        }
      } else {
        val headers = TSVFormatter.makeEntityHeaders(entityType, metadata.attributeNames, attributeNames)
        streamSingularType(ctx, workspaceNamespace, workspaceName, entityType, entityQueries, metadata, headers, attributeNames).onFailure {
          case t: Throwable => handleStreamException(ctx, t)
        }
      }
      Future(())
    }
  }.recoverWith {
    // Standard exceptions have to be handled as a completed request
    case t: Throwable => handleStandardException(ctx, t)
  }


  /*
   * Helper Methods
   */

  // Standard exceptions have to be handled as a completed request
  private def handleStandardException(ctx: RequestContext, t: Throwable): Future[Unit] = {
    val errorReport = t match {
      case f: FireCloudExceptionWithErrorReport => f.errorReport
      case _ => ErrorReport(StatusCodes.InternalServerError, s"FireCloudException: Error generating entity download: ${t.getMessage}")
    }
    Future(ctx.complete(HttpResponse(
      status = errorReport.statusCode.getOrElse(StatusCodes.InternalServerError),
      entity = HttpEntity(ContentTypes.`application/json`, errorReport.toJson.compactPrint))))
  }

  // Stream exceptions have to be handled by directly closing out the RequestContext responder stream
  private def handleStreamException(ctx: RequestContext, t: Throwable): Unit = {
    val message = t match {
      case f: FireCloudExceptionWithErrorReport => s"FireCloudException: Error generating entity download: ${f.errorReport.message}"
      case _ => s"FireCloudException: Error generating entity download: ${t.getMessage}"
    }
    ctx.responder ! MessageChunk(message)
    ctx.responder ! ChunkedMessageEnd
  }

  /**
    * General Approach
    * 1. Define a `Source` of entity queries
    * 2. Run the source events through a `Flow`.
    * 3. Flow sends events (batch of entities) to a streaming output actor
    * 4. Return a Done to the calling route when complete.
    */
  private def streamSingularType(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, entityType: String, entityQueries: Seq[EntityQuery], metadata: EntityTypeMetadata, headers: IndexedSeq[String], attributeNames: Option[IndexedSeq[String]]): Future[Done] = {
    // The output to the user
    val streamingActorRef = actorRefFactory.actorOf(Props(new StreamingActor(ctx, ContentTypes.`text/plain`, entityType + ".txt")))

    // The Source
    val entityQuerySource = Source(entityQueries.toStream)

    // The Flow. Using mapAsync(1) ensures that we run 1 batch at a time through this side-affecting process.
    val flow = Flow[EntityQuery].mapAsync(1) { query =>
      getEntitiesFromQuery(workspaceNamespace, workspaceName, entityType, query) map { entities =>
        sendRowsAsChunks(streamingActorRef, query, entityQueries.size, entityType, headers, entities)
      }
    }

    // Ignore the result - we don't need to remember anything about this operation.
    val sink = Sink.ignore

    // finally, run it:
    entityQuerySource.via(flow).runWith(sink)
  }

  // TODO: Sort out how to run this in stages, i.e. headers, then rows.
  // TODO: Sort out how to use a single source and split to outputs, i.e. one source out to both flows
  // TODO: Need error handling at the runWith level for each stream-flow
  private def streamCollectionType(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, entityType: String, entityQueries: Seq[EntityQuery], metadata: EntityTypeMetadata, attributeNames: Option[IndexedSeq[String]]): Future[Done] = {

    // Future of all entities
    val entityBatches = Future.traverse(entityQueries) { query =>
      getEntitiesFromQuery(workspaceNamespace, workspaceName, entityType, query)
    } map(_.flatten)

    // Source from that future
    val entityBatchSource = Source.fromFuture(entityBatches)

    // Two File sinks, one for each kind of entity set file needed.
    // The temp files will end up zipped and streamed when complete.
    val tempEntityFile = File.newTemporaryFile()
    val entitySink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(tempEntityFile.path)
    val tempMembershipFile = File.newTemporaryFile()
    val membershipSink: Sink[ByteString, Future[IOResult]]  = FileIO.toPath(tempMembershipFile.path)



    // Run the Entity flow
    val entityHeaders = TSVFormatter.makeEntityHeaders(entityType, metadata.attributeNames, attributeNames)
    Source.single(ByteString(entityHeaders.mkString("\t") + "\n")).runWith(entitySink)
    // Entity TSV Flow
    val entityWriterFlow = Flow[Seq[Entity]].map { entities =>
      val rows = TSVFormatter.makeEntityRows(entityType, entities, entityHeaders)
      ByteString(rows.map{ _.mkString("\t") }.mkString("\n") + "\n")
    }
    def entityFileStreamResult: Future[IOResult] = entityBatchSource.via(entityWriterFlow).runWith(entitySink)



    // Run the Membership flow
    val membershipHeaders = TSVFormatter.makeMembershipHeaders(entityType)
    Source.single(ByteString(membershipHeaders.mkString("\t") + "\n")).runWith(membershipSink)
    // Membership TSV Flow
    val membershipWriterFlow = Flow[Seq[Entity]].map { entities =>
      val rows = TSVFormatter.makeMembershipRows(entityType, entities)
      ByteString(rows.map{ _.mkString("\t") }.mkString("\n") + "\n")
    }
    def membershipFileStreamResult: Future[IOResult] = entityBatchSource.via(membershipWriterFlow).runWith(membershipSink)


    val fileStreamResultSuccess = for {
      eFileResult <- entityFileStreamResult
      mFileResult <- membershipFileStreamResult
    } yield eFileResult.wasSuccessful && mFileResult.wasSuccessful


    fileStreamResultSuccess map { s =>
      if (s) {
        val zipFile = writeFilesToZip(entityType, tempEntityFile, tempMembershipFile)
        // The output to the user
        lazy val streamingActorRef = actorRefFactory.actorOf(Props(new StreamingActor(ctx, ContentTypes.`application/octet-stream`, entityType + ".zip")))
        zipFile map { f =>
          streamingActorRef ! FirstChunk(HttpData.apply(f.byteArray), 0)
        }
      } else {
        throw new FireCloudExceptionWithErrorReport(ErrorReport(s"Unable to collect entity metadata for $workspaceNamespace:$workspaceName:$entityType"))
      }
    }

    Future(Done)
  }

  private def sendRowsAsChunks(actorRef: ActorRef, query: EntityQuery, querySize: Int, entityType: String, headers: IndexedSeq[String], entities: Seq[Entity]): Unit = {
    val rows = TSVFormatter.makeEntityRows(entityType, entities, headers)
    val remaining = querySize - query.page + 1
    // Send headers as the first chunk of data
    if (query.page == 1) { actorRef ! FirstChunk(HttpData(headers.mkString("\t") + "\n"), remaining)}
    // Send entities
    actorRef ! NextChunk(HttpData(rows.map { _.mkString("\t") }.mkString("\n") + "\n"), remaining - 1)
  }

  private def writeFilesToZip(entityType: String, entityTSV: File, membershipTSV: File): Future[File] = {
    Future {
      val zipFile = File.newTemporaryDirectory()
      membershipTSV.moveTo(zipFile/s"${entityType}_membership.tsv")
      entityTSV.moveTo(zipFile/s"${entityType}_entity.tsv")
      zipFile.zip()
    }
  }

  private def getEntityQueries(metadata: EntityTypeMetadata, entityType: String): Seq[EntityQuery] = {
    val pageSize = FireCloudConfig.Rawls.defaultPageSize
    val filteredCount = metadata.count
    val sortField = entityType + "_id"
    val pages = Math.ceil(filteredCount.toDouble / pageSize.toDouble).toInt
    (1 to pages) map { page =>
      EntityQuery(page = page, pageSize = pageSize, sortField = sortField, sortDirection = SortDirections.Ascending, filterTerms = None)
    }
  }

  private def getEntityTypeMetadata(workspaceNamespace: String, workspaceName: String, entityType: String): Future[EntityTypeMetadata] = {
    rawlsDAO.getEntityTypes(workspaceNamespace, workspaceName).
      map (_.getOrElse(entityType,
        throw new FireCloudExceptionWithErrorReport(ErrorReport(s"Unable to collect entity metadata for $workspaceNamespace:$workspaceName:$entityType")))
      )
  }

  private def getEntitiesFromQuery(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery): Future[Seq[Entity]] = {
    rawlsDAO.queryEntitiesOfType(workspaceNamespace, workspaceName, entityType, query) map {
      response => response.results
    }
  }

}
