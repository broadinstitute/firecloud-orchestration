package org.broadinstitute.dsde.firecloud.service

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorSystem, Props}
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.RequestCompleteWithErrorReport
import org.broadinstitute.dsde.firecloud.service.StreamingActor._
import spray.http.HttpEntity.Empty
import spray.http.StatusCodes.InternalServerError
import spray.http._
import spray.routing.RequestContext

object StreamingActor {

  case object FirstChunk
  case object ChunkAck

  case class FromHttpData(ctx: RequestContext, filename: String, contentType: ContentType, stream: Stream[HttpData]) extends StreamingActor

  def props(ctx: RequestContext, filename: String, contentType: ContentType, stream: Stream[Array[Byte]]): Props =
    Props(FromHttpData(ctx, filename, contentType, stream.map(HttpData.apply)))

}

trait StreamingActor extends Actor with FireCloudRequestBuilding with LazyLogging {

  def ctx: RequestContext
  def filename: String
  def contentType: ContentType
  def stream: Stream[HttpData]
  val headers = List(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> filename)))

  implicit val system: ActorSystem = context.system

  lazy val chunkIterator: Iterator[HttpData] = stream.iterator

  self ! FirstChunk

  def receive: Receive = {

    case FirstChunk if chunkIterator.hasNext =>
      val responseStart = HttpResponse(entity = HttpEntity(contentType, chunkIterator.next()), headers = headers)
      ctx.responder ! ChunkedResponseStart(responseStart).withAck(ChunkAck)

    // data stream is empty. Respond with Content-Length: 0 and stop
    case FirstChunk =>
      ctx.responder ! HttpResponse(entity = Empty, headers = headers)
      context.stop(self)

    // send next chunk to client
    case ChunkAck if chunkIterator.hasNext =>
      val nextChunk = MessageChunk(chunkIterator.next())
      ctx.responder ! nextChunk.withAck(ChunkAck)

    // all chunks were sent. stop.
    case ChunkAck =>
      ctx.responder ! ChunkedMessageEnd
      context.stop(self)

    // handle failures
    case Failure(e) =>
      logger.error(e.getMessage)
      context.parent ! RequestCompleteWithErrorReport(InternalServerError, "Streaming request failed: " + e.getMessage, e)

    // anything else
    case x =>
      logger.error(x.toString)
      unhandled(x)
  }

}
