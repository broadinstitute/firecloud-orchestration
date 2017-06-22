package org.broadinstitute.dsde.firecloud.utils

import akka.actor.{Actor, ActorContext}
import org.slf4j.{Logger, LoggerFactory}
import spray.http._
import spray.routing.{HttpService, RequestContext}

object StreamingActor {
  case class FirstChunk(httpData: HttpData, remaining: Int)
  case class NextChunk(httpData: HttpData, remaining: Int)
  case class Ok(remaining: Int)
  case object ChunkEnd
}

class StreamingActor(ctx: RequestContext, contentType: ContentType, contentDisposition: String) extends Actor with HttpService {

  private lazy val logger: Logger = LoggerFactory.getLogger(getClass)
  private val keepAlive = HttpHeaders.Connection("Keep-Alive")
  private val disposition = HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> contentDisposition))

  import StreamingActor._

  def actorRefFactory: ActorContext = context

  def receive: Receive = {

    // send first chunk to client
    case FirstChunk(httpData: HttpData, remaining: Int) =>
      logger.debug(s"Writing first chunk: ${httpData.length}, remaining: $remaining")
      val responseStart = HttpResponse(entity = HttpEntity(contentType, httpData), headers = List(keepAlive, disposition))
      ctx.responder ! ChunkedResponseStart(responseStart).withAck(Ok(remaining))

    // send next chunk to client
    case NextChunk(httpData: HttpData, remaining: Int) =>
      logger.debug(s"Writing next chunk: ${httpData.length}, remaining: $remaining")
      val nextChunk = MessageChunk(httpData)
      ctx.responder ! nextChunk.withAck(Ok(remaining))

    // all chunks were sent. stop.
    case ChunkEnd =>
      logger.debug("Ending message.")
      ctx.responder ! ChunkedMessageEnd
      context.stop(self)

    //
    case x => unhandled(x)

  }


}