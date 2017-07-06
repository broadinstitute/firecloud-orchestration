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

    // Headers
    val entityHeaders = TSVFormatter.makeEntityHeaders(entityType, metadata.attributeNames, attributeNames)
    val eHeaderResult = Source.single(ByteString(entityHeaders.mkString("\t") + "\n")).runWith(entitySink)
    val membershipHeaders = TSVFormatter.makeMembershipHeaders(entityType)
    val mHeaderResult = Source.single(ByteString(membershipHeaders.mkString("\t") + "\n")).runWith(membershipSink)

    // Check the header generation to the files.
    val headerResult = for {
      eHeader <- eHeaderResult
      mHeader <- mHeaderResult
    } yield eHeader.wasSuccessful && mHeader.wasSuccessful

    // If that worked, then continue writing entity content to the same files
    val fileStreamIOResults: Future[(Future[IOResult], Future[IOResult])] = headerResult.map { s =>
      if (!s) {
        throw new FireCloudExceptionWithErrorReport(ErrorReport(s"Unable to write entity header data for $workspaceNamespace:$workspaceName:$entityType"))
      } else {
        // Run the Split Entity Flow that pipes entities through the two flows to the two file sinks
        // Result of this will be a Future[(Future[IOResult], Future[IOResult])] that represents the
        // success or failure of streaming content to the file sinks.
        RunnableGraph.fromGraph(GraphDSL.create(entitySink, membershipSink)((_, _)) { implicit builder =>
          (eSink, mSink) =>
          import GraphDSL.Implicits._

          // Source
          val entitySource: Outlet[Seq[Entity]] = builder.add(entityBatchSource).out

          // Flows
          val splitter: UniformFanOutShape[Seq[Entity], Seq[Entity]] = builder.add(Broadcast[Seq[Entity]](2))
          val entityFlow: FlowShape[Seq[Entity], ByteString] = builder.add(Flow[Seq[Entity]].map { entities =>
            val rows = TSVFormatter.makeEntityRows(entityType, entities, entityHeaders)
            ByteString(rows.map{ _.mkString("\t") }.mkString("\n") + "\n")
          })
          val membershipFlow: FlowShape[Seq[Entity], ByteString] = builder.add(Flow[Seq[Entity]].map { entities =>
            val rows = TSVFormatter.makeMembershipRows(entityType, entities)
            ByteString(rows.map{ _.mkString("\t") }.mkString("\n") + "\n")
          })

          // Graph
                     entitySource ~> splitter
          eSink <~     entityFlow <~ splitter
          mSink <~ membershipFlow <~ splitter
          ClosedShape
        }).run()
      }
    }

    // Check that each file is completed
    val fileStreamResult = fileStreamIOResults flatMap { fTuple =>
      for {
        eResult <- fTuple._1
        mResult <- fTuple._2
      } yield eResult.wasSuccessful && mResult.wasSuccessful
    }

    // And then map those files to a ZIP.
    fileStreamResult map { s =>
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
