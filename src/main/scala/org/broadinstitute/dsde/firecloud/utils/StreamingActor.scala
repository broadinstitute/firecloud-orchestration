package org.broadinstitute.dsde.firecloud.utils

import akka.actor.{Actor, ActorContext}
import com.typesafe.scalalogging.slf4j.LazyLogging
import spray.http._
import spray.routing.{HttpService, RequestContext}

object StreamingActor {
  case class FirstChunk(httpData: HttpData, remaining: Int)
  case class NextChunk(httpData: HttpData, remaining: Int)
  case class Ok(remaining: Int)
}

class StreamingActor(ctx: RequestContext, contentType: ContentType, fileName: String) extends Actor with HttpService with LazyLogging {

  private val keepAlive = HttpHeaders.Connection("Keep-Alive")
  private val disposition = HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> fileName))

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

    // This case comes back from the client
    // all chunks were sent. stop.
    case Ok(remaining: Int) =>
      if (remaining == 0) {
        ctx.responder ! ChunkedMessageEnd
        context.stop(self)
      }

    //
    case x =>
      logger.error(s"Unhandled case: $x")
      unhandled(x)

  }


}