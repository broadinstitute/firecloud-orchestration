package org.broadinstitute.dsde.firecloud.utils

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{Host, `Timeout-Access`}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.{BasicDirectives, RouteDirectives}
import akka.stream.scaladsl.{Sink, Source}
import com.google.common.net.UrlEscapers
import com.typesafe.scalalogging.Logger
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait StreamingPassthrough
  extends BasicDirectives
    with RouteDirectives {

  // Log under the StreamingPassthrough class, not whatever class mixes this in.
  protected lazy val streamingPassthroughLogger: Logger =
    Logger(LoggerFactory.getLogger("org.broadinstitute.dsde.firecloud.utils.StreamingPassthrough"))

  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext
  val passthroughErrorReportSource: ErrorReportSource = ErrorReportSource("Orchestration")

  def escapePathSegment(pathString: String) = {
    UrlEscapers.urlPathSegmentEscaper().escape(pathString)
  }

  /**
    * Passes through, to remoteBaseUri, all requests that match or start with the
    * currently matched path.
    *
    * @param remoteBaseUri the remote system to use as target for passthrough requests
    */
  def streamingPassthrough(remoteBaseUri: Uri): Route = {
    extractMatchedPath { localBasePath =>
      passthroughImpl(localBasePath, remoteBaseUri)
    }
  }

  /**
    * Passes through, to a remote server, all requests that match or start with the
    * supplied local path.
    *
    * @param passthroughMapping in the form `localBasePath -> remoteBaseUri`, where
    *                           the localBasePath is the Orchestration-local path
    *                           which must be matched for passthroughs, and remoteBaseUri
    *                           is the fully-qualified URL to a remote system
    *                           to use as target for passthrough requests.
    */
  def streamingPassthrough(passthroughMapping: (Uri.Path, Uri)): Route = {
    passthroughImpl(passthroughMapping._1, passthroughMapping._2)
  }

  /**
    * The passthrough implementation:
    *   - `mapRequest` to transform the incoming request to what we want to send to the remote system
    *   - `extractRequest` so we have the transformed request as an object
    *   - call the remote system and reply to the user via `routeResponse` streaming
   */
  private def passthroughImpl(localBasePath: Uri.Path, remoteBaseUri: Uri): Route = {
    mapRequest(transformToPassthroughRequest(localBasePath, remoteBaseUri)) {
      extractRequest { req =>
        complete {
          routeResponse(req)
        }
      }
    }
  }


  /**
    * Accepts an http request from an end user to Orchestration,
    * and converts it to a request suitable to send to a remote system
    * for passthroughs:
    *   - rewrite the URI to be valid for the remote system
    *   - strip any http headers, such as Timeout-Access, that are invalid to send to the remote system
    *
    * This allows Orchestration to act as an API gateway,
    * passing the user's request mostly untouched to another service.
    *
    * @param req the request inbound to Orchestration
    * @return the outbound request to be sent to another service
    */
  def transformToPassthroughRequest(localBasePath: Uri.Path, remoteBaseUri: Uri)(req: HttpRequest): HttpRequest = {
    // Convert the URI to the one suitable for the remote system
    val targetUri = convertToRemoteUri(req.uri, localBasePath, remoteBaseUri)
    // Remove unwanted headers:
    // Timeout-Access: Akka automatically adds a Timeout-Access to the request
    // for internal use in managing timeouts; see akka.http.server.request-timeout.
    // This header is not legal to pass to an external remote system.
    //
    // Host: The Nginx ingress controller used in Terra's BEE environments uses the Host header for routing,
    // so we remove and set it with targetUri host
    val filteredHeaders = req.headers.filter { hdr =>
      hdr.isNot(`Timeout-Access`.lowercaseName) &&
        hdr.isNot(Host.lowercaseName)
    }

    val targetHeaders = filteredHeaders :+ Host(targetUri.authority.host)

    // TODO: what should this log?
    streamingPassthroughLogger.info(s"Passthrough API called. Forwarding call: ${req.method} ${req.uri} => $targetUri")

    // return a copy of the inbound request, using the new URI and new headers.
    req.withUri(targetUri).withHeaders(targetHeaders)
  }

  // TODO: get feedback on this method. Not happy having rawls specific request handling here (rather have it on CromiamApiService)
  /**
   * Private method that modifies the requestPath for the one workflow passthrough to Rawls
   * due to the fact that the Rawls endpoint has a different URI construction, making suffix
   * passthroughs inept
   * @param requestUri
   * @param remoteBaseUri
   * @return the requestPath (String) suitable to be sent to the remote system
   */
  private def requestStringConstruction(requestUri: Uri, remoteBaseUri: Uri) : String = {
    val requestPath = requestUri.path.toString
    if (
        FireCloudConfig.Rawls.authUrl.contains(remoteBaseUri.authority.toString()) &&
        requestPath.contains("/backend/metadata/")
    ) {
      requestPath.replace("backend/metadata", "genomics")
    } else requestPath
  }

  /**
    * Inspects the URI for an actual end-user request to Orchestration, finds the portion
    * of that Uri after the localBasePath, and appends that remainder to the remoteBaseUri.
    *
    * The end result is a URI suitable to send to a remote system.
    *
    * @param requestUri the end-user's request to Orchestration
    * @param localBasePath the portion of the path to strip off
    * @param remoteBaseUri the remote system to use as passthrough target
    * @return the URI suitable for sending to the remote system
    */
  def convertToRemoteUri(requestUri: Uri, localBasePath: Uri.Path, remoteBaseUri: Uri): Uri = {
    // Ensure the incoming request starts with the localBasePath. Abort if it doesn't.
    // This condition should only be caused by developer error in which the streamingPassthrough
    // directive is incorrectly configured inside a route.
    if (!requestUri.path.startsWith(localBasePath)) {
      throw new Exception(s"request path doesn't start properly: ${requestUri.path} does not start with $localBasePath")
    }

    // find every part of the actual request path, minus the base.
    val baseString = localBasePath.toString
    val requestString = requestStringConstruction(requestUri, remoteBaseUri)
    val remainder = Uri.Path(requestString.replaceFirst(baseString, ""))
    // append the remainder to the remoteBaseUri's path
    val remotePath = remoteBaseUri.path ++ remainder

    // build a new Uri for the remote system. This uses:
    // * the scheme, host, and port as defined in remoteBaseUri (host and port are combined into authority)
    // * the path built from the remoteBaseUri path + remainder
    // * everything else (querystring, fragment, userinfo) from the original request
    requestUri.copy(
      scheme = remoteBaseUri.scheme,
      authority = remoteBaseUri.authority,
      path = remotePath)
  }

  /**
    * An akka-streaming flow which sends an HttpRequest to a remote server,
    * then replies with the HttpResponse from that remote server.
    *
    * @param req the request to send to the remote server
    * @return the Future-wrapped response from the remote server
    */
  private def routeResponse(req: HttpRequest): Future[HttpResponse] = {
    val flowFuture = Source.single((req, NotUsed))
      .via(Http().superPool[NotUsed]())
      .runWith(Sink.head)

    flowFuture map { responseTuple =>
      responseTuple._1 match {
        case Success(resp) =>
          // reply with the response from the remote server, even if the remote
          // server returned a 4xx or 5xx
          resp
        case Failure(ex) =>
          // the remote server did not respond at all, so we have nothing to use for the reply;
          // throw an error
          throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, ex)(passthroughErrorReportSource))
      }
    }
  }






}
