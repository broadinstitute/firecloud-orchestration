package org.broadinstitute.dsde.firecloud.utils

import akka.actor.{Actor, Props}
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.utils.StreamingActor._
import spray.http.{ContentType, _}
import spray.routing.RequestContext

object StreamingActor {
  case class FirstChunk(httpData: HttpData, remaining: Int)
  case class NextChunk(httpData: HttpData, remaining: Int)
  case class Ok(remaining: Int)
  def props(ctx: RequestContext, contentType: ContentType, fileName: String) =
    Props(new StreamingActor(ctx, contentType, fileName))
}

class StreamingActor(ctx: RequestContext, contentType: ContentType, fileName: String) extends Actor with LazyLogging {

  private val keepAlive = HttpHeaders.Connection("Keep-Alive")
  private val disposition = HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> fileName))

  def receive: Receive = {

    // send first chunk to client
    case FirstChunk(httpData: HttpData, remaining: Int) =>
      val responseStart = HttpResponse(entity = HttpEntity(contentType, httpData), headers = List(keepAlive, disposition))
      ctx.responder ! ChunkedResponseStart(responseStart).withAck(Ok(remaining))

    // send next chunk to client
    case NextChunk(httpData: HttpData, remaining: Int) =>
      val nextChunk = MessageChunk(httpData)
      ctx.responder ! nextChunk.withAck(Ok(remaining))

    // This case comes back from the client
    // all chunks were sent. stop.
    case Ok(remaining: Int) =>
      if (remaining == 0) {
        ctx.responder ! ChunkedMessageEnd
        context.stop(self)
      }

  }

}