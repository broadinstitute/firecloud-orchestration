package org.broadinstitute.dsde.firecloud.service

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import org.broadinstitute.dsde.firecloud.model.RequestCompleteWithErrorReport
import org.broadinstitute.dsde.firecloud.service.StreamingActor._
import spray.http.HttpEntity.Empty
import spray.http.StatusCodes.InternalServerError
import spray.http._
import spray.routing.RequestContext


object StreamingActor {

  case object FirstChunk
  case object ChunkAck

  case class FromHttpData(ctx: RequestContext, contentType: ContentType, stream: Stream[HttpData]) extends StreamingActor

}


trait StreamingActorCreator {

  def propsFromString(ctx: RequestContext, contentType: ContentType, stream: Stream[String]): Props =
    Props(FromHttpData(ctx, contentType, stream.map(HttpData.apply)))

}


trait StreamingActor extends Actor with FireCloudRequestBuilding with ActorLogging {

  def ctx: RequestContext
  def contentType: ContentType
  def stream: Stream[HttpData]

  implicit val system: ActorSystem = context.system

  lazy val chunkIterator: Iterator[HttpData] = stream.iterator

  self ! FirstChunk

  def receive: Receive = {

    case FirstChunk if chunkIterator.hasNext =>
      val responseStart = HttpResponse(entity = HttpEntity(contentType, chunkIterator.next()))
      ctx.responder ! ChunkedResponseStart(responseStart).withAck(ChunkAck)

    // data stream is empty. Respond with Content-Length: 0 and stop
    case FirstChunk =>
      ctx.responder ! HttpResponse(entity = Empty)
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
      log.error(e.getMessage)
      context.parent ! RequestCompleteWithErrorReport(InternalServerError, "Streaming request failed: " + e.getMessage, e)

    // anything else
    case x =>
      log.error(x.toString)
      unhandled(x)
  }

}
