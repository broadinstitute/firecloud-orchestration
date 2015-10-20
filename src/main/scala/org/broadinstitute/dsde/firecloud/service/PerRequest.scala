package org.broadinstitute.dsde.firecloud.service

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import org.broadinstitute.dsde.firecloud.model.{HttpResponseWithErrorReport, ErrorReport}
import org.broadinstitute.dsde.firecloud.service.PerRequest._
import org.broadinstitute.dsde.firecloud.HttpClient
import spray.http.StatusCodes._
import spray.http.{HttpHeader, HttpHeaders, HttpRequest, RequestProcessingException}
import spray.httpx.marshalling.ToResponseMarshaller
import spray.routing.RequestContext

import scala.concurrent.duration._

/**
 * This actor controls the lifecycle of a request. It is responsible for forwarding the initial message
 * to a target handling actor. This actor waits for the target actor to signal completion (via a message),
 * timeout, or handle an exception. It is this actors responsibility to respond to the request and
 * shutdown itself and child actors.
 *
 * Request completion can be signaled in 2 ways:
 * 1) with just a response object
 * 2) with a RequestComplete message which can specify http status code as well as the response
 */
trait PerRequest extends Actor {
  import context._

  // JSON Serialization Support
  import spray.httpx.SprayJsonSupport._
  import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

  def r: RequestContext
  def target: ActorRef
  def message: AnyRef
  def timeout: Duration

  setReceiveTimeout(timeout)
  target ! message

  def receive = {
    case RequestComplete_(response, marshaller) => complete(response)(marshaller)
    case RequestCompleteWithHeaders_(response, headers, marshaller) => complete(response, headers: _*)(marshaller)
    case ReceiveTimeout => complete(HttpResponseWithErrorReport(GatewayTimeout, "Request Timed Out"))
    case x =>
      val message = "Unsupported response message sent to PerRequest actor: " + Option(x).getOrElse("null").toString
      system.log.error(message)
      complete(HttpResponseWithErrorReport(InternalServerError, message))
  }

  /**
   * Complete the request sending the given response and status code
   * @param response to send to the caller
   * @param marshaller to use for marshalling the response
   * @return
   */
  private def complete[T](response: T, headers: HttpHeader*)(implicit marshaller: ToResponseMarshaller[T]) = {
    r.withHttpResponseHeadersMapped(h => (h ++ headers).filterNot(isAutomaticHeader)).complete(response)
    stop(self)
  }

  private def isAutomaticHeader(h: HttpHeader): Boolean = h match {
    case _:HttpHeaders.Date => true
    case _:HttpHeaders.Server => true
    case _:HttpHeaders.`Content-Type` => true
    case _:HttpHeaders.`Content-Length` => true
    case _:HttpHeaders.`Transfer-Encoding` => true
    case _ => false
  }

  override val supervisorStrategy =
    OneForOneStrategy() {

      case e: RequestProcessingException =>
        r.complete(HttpResponseWithErrorReport(InternalServerError, e))
        Stop
      case e: Throwable =>
        r.complete(HttpResponseWithErrorReport(InternalServerError, e))
        Stop
    }
}


object PerRequest {

  sealed trait PerRequestMessage
  /**
   * Report complete, follows same pattern as spray.routing.RequestContext.complete; examples of how to call
   * that method should apply here too. E.g. even though this method has only one parameter, it can be called
   * with 2 where the first is a StatusCode: RequestComplete(StatusCode.Created, response)
   */
  case class RequestComplete[T](response: T)(implicit val marshaller: ToResponseMarshaller[T]) extends PerRequestMessage

  /**
   * Report complete with response headers. To response with a special status code the first parameter can be a
   * tuple where the first element is StatusCode: RequestCompleteWithHeaders((StatusCode.Created, results), header).
   * Note that this is here so that RequestComplete above can behave like spray.routing.RequestContext.complete.
   */
  case class RequestCompleteWithHeaders[T](response: T, headers: HttpHeader*)(implicit val marshaller: ToResponseMarshaller[T]) extends PerRequestMessage

  /** allows for pattern matching with extraction of marshaller */
  private object RequestComplete_ {
    def unapply[T >: Any](requestComplete: RequestComplete[T]) = Some((requestComplete.response, requestComplete.marshaller))
  }

  /** allows for pattern matching with extraction of marshaller */
  private object RequestCompleteWithHeaders_ {
    def unapply[T >: Any](requestComplete: RequestCompleteWithHeaders[T]) = Some((requestComplete.response, requestComplete.headers, requestComplete.marshaller))
  }

  case class WithProps(r: RequestContext, props: Props, message: AnyRef, name: String, timeout: Duration) extends PerRequest {
    lazy val target = context.actorOf(props, name)
  }
}

/**
 * Provides factory methods for creating per request actors
 */
trait PerRequestCreator {
  implicit def actorRefFactory: ActorRefFactory

  def perRequest(r: RequestContext, props: Props, message: AnyRef, name: String = java.lang.Thread.currentThread.getStackTrace()(1).getMethodName, timeout: Duration = 1.minutes) =
    actorRefFactory.actorOf(Props(new WithProps(r, props, message, name + System.nanoTime(), timeout)), name + System.nanoTime())

  /** convenience for HttpClient */
  def externalHttpPerRequest(r: RequestContext, request: HttpRequest) =
    perRequest(r, Props(new HttpClient(r)), HttpClient.PerformExternalRequest(request))
}
