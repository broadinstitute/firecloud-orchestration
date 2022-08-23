package org.broadinstitute.dsde.firecloud.utils

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.`Timeout-Access`
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.{BasicDirectives, RouteDirectives}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}

import scala.annotation.tailrec
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
    // TODO: unit tests!
    // TODO: handle warnings about "HTTP header 'Timeout-Access: <function1>' is not allowed in requests"
    // TODO: any other headers that should be removed?
    // TODO: don't match Timeout-Access header by name
    val targetUri = convertToTargetUri(req.uri, localBasePath, remoteBaseUri)

    logger.warn(s"${req.method} ${req.uri} => $targetUri")

    val localHeaders = req.headers
    val targetHeaders = localHeaders.filterNot(_.name() == `Timeout-Access`.name)

    req.withUri(targetUri).withHeaders(targetHeaders)
  }

  def convertToTargetUri(requestUri:Uri, localBasePath: Uri.Path, remoteBaseUri: Uri): Uri = {
    val requestPath: Path = requestUri.path

    // Ensure the incoming request starts with the localBasePath. Abort if it doesn't.
    // This condition should only be caused by developer error in which the streamingPassthrough
    // directive is incorrectly configured inside a route.
    if (!requestPath.startsWith(localBasePath)) {
      throw new Exception(s"doesn't start properly: $requestPath does not start with $localBasePath")
    }

    @tailrec
    def findPathRemainder(base: Path, actual: Path): Path = {
      if (base.isEmpty || !actual.startsWith(base)) {
        logger.warn(s"***** DONE with drilldown: $actual")
        actual
      } else {
        logger.warn(s"***** drilling down: $base | $actual")
        findPathRemainder(base.tail, actual.tail)
      }
    }

    // find the "remainder" - the portion of the actual request path
    // that comes after the localBasePath
    val remainder = findPathRemainder(localBasePath, requestPath)
    // append the remainder to the remoteBaseUri's path
    val remotePath = remoteBaseUri.path ++ remainder

    // build a new Uri for the remote system. This uses:
    // * the scheme, host, and port as defined in remoteBaseUri (host and port are combined into authority)
    // * the path built from the remoteBaseUri path + remainder
    // * everything else (querystring, fragment, userinfo) from the original request
    val targetUri = requestUri.copy(
      scheme = remoteBaseUri.scheme,
      authority = remoteBaseUri.authority,
      path = remotePath)

    targetUri
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
