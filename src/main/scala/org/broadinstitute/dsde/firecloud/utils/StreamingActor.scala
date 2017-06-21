package org.broadinstitute.dsde.firecloud.utils

import akka.actor.{Actor, ActorContext}
import org.slf4j.{Logger, LoggerFactory}
import spray.http._
import spray.routing.{HttpService, RequestContext}

object StreamingActor {
  case class FirstChunk(httpData: HttpData)
  case class NextChunk(httpData: HttpData)
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
    case FirstChunk(httpData: HttpData) =>
      logger.debug(s"Writing first chunk: ${httpData.length}")
      val responseStart = HttpResponse(entity = HttpEntity(contentType, httpData), headers = List(keepAlive, disposition))
      ctx.responder ! ChunkedResponseStart(responseStart)

    // send next chunk to client
    case NextChunk(httpData: HttpData) =>
      logger.debug(s"Writing next chunk: ${httpData.length}")
      val nextChunk = MessageChunk(httpData)
      ctx.responder ! nextChunk

    // all chunks were sent. stop.
    case ChunkEnd =>
      logger.debug("Ending message.")
      ctx.responder ! ChunkedMessageEnd
      context.stop(self)

    //
    case x => unhandled(x)

  }


}