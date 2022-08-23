package org.broadinstitute.dsde.firecloud.utils

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.`Timeout-Access`
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.{BasicDirectives, RouteDirectives}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait StreamingPassthrough
  extends BasicDirectives
    with RouteDirectives
    with LazyLogging {

  // TODO: logging. Log this under the StreamingPassthrough class, not whatever class extends this.

  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext
  val passthroughErrorReportSource: ErrorReportSource = ErrorReportSource("Orchestration")

  /**
    * Accepts an http request from an end user to Orchestration,
    * and rewrites the URI for that request to target an external service.
    *
    * Additionally, strip any http headers from the outbound request that
    * are invalid, such as Timeout-Access.
    *
    * This allows Orchestration to act as an API gateway,
    * passing the user's request mostly untouched to another service.
    *
    * @param req the request inbound to Orchestration
    * @return the outbound request to be sent to another service
    */
  def transformToPassthroughRequest(localBasePath: Uri.Path, remoteBaseUri: Uri)(req: HttpRequest): HttpRequest = {
    // TODO: do this without going toString() everywhere!
    // TODO: unit tests!
    // TODO: handle warnings about "HTTP header 'Timeout-Access: <function1>' is not allowed in requests"
    // TODO: any other headers that should be removed?
    // TODO: don't match Timeout-Access header by name

    val localUri:Uri = req.uri
    val localPath:Uri.Path = localUri.path
    if (!localPath.toString().startsWith(localBasePath.toString())) {
      throw new Exception(s"doesn't start properly: $localPath does not start with $localBasePath")
    }
    val extra: String = localPath.toString().replaceFirst(localBasePath.toString(), "")

    val tempUri = Uri(remoteBaseUri.toString() + extra)

    val targetUri = localUri.copy(scheme = tempUri.scheme, authority = tempUri.authority, path = tempUri.path)

    val localHeaders = req.headers
    val targetHeaders = localHeaders.filterNot(_.name() == `Timeout-Access`.name)

    req.withUri(targetUri).withHeaders(targetHeaders)
  }

  /**
    *
    * @param req
    * @return
    */
  def routeResponse(req: HttpRequest): Future[HttpResponse] = {
    val flowFuture = Source.single((req, NotUsed))
      .via(Http().superPool[NotUsed]())
      .runWith(Sink.head)

    flowFuture map { tuple =>
      tuple._1 match {
        case Success(resp) => resp
        case Failure(ex) =>
          throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, ex)(passthroughErrorReportSource))
      }
    }
  }

  /**
    *
    * @return
    */
  def streamingPassthrough(passthroughMapping: (Uri.Path, Uri)): Route = {
    passthroughMapping match {
      case (localBasePath, remoteBaseUri) =>
        // TODO: unit tests using mockserver: do errors bubble up? Are response codes honored? Is auth passed? Are success payloads bubbled up?
        mapRequest(transformToPassthroughRequest(localBasePath, remoteBaseUri)) {
          extractRequest { req =>
            complete {
              routeResponse(req)
            }
          }
        }
    }
  }




}
