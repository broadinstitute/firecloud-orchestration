package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.headers.{Authorization, `Content-Type`}
import akka.http.scaladsl.model.{HttpMethod, Uri}
import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.parboiled.common.FileUtils

import scala.util.Try

object FireCloudDirectiveUtils {
  def encodeUri(path: String): String = {
    val pattern = """(https|http)://([^/\r\n]+?)(:\d+)?(/[^\r\n]*)?""".r

    def toUri(url: String) = url match {
      case pattern(theScheme, theHost, thePort, thePath) =>
        val p: Int = Try(thePort.replace(":","").toInt).toOption.getOrElse(0)
        Uri.from(scheme = theScheme, port = p, host = theHost, path = thePath)
    }
    toUri(path).toString
  }

  // TODO: should this pass through any other headers, such as Cookie?
  /* This list controls which headers from the original request are passed through to the
   * target service. It is important that some headers are NOT passed through:
   *     - if the Host header is passed through, it will not match the target service's host,
   *        and some servers - notably App Engine - will reject the request.
   *     - if headers that control the http protocol such as Accept-Encoding or Connection
   *        are passed through, they may not reflect reality. The original request may have
   *        come from a browser that supports different encodings or connection protocols
   *        than the service-to-service request we're about to make.
   *     - if headers that inform the target such as User-Agent, Referer or X-Forwarded-For
   *        are passed through, they will be misleading, as they reflect the original request
   *        and not the service-to-service request we're about to make.
   */
  final val allowedPassthroughHeaders = List(Authorization, `Content-Type`).map(_.lowercaseName)

}

trait FireCloudDirectives extends Directives with RequestBuilding with RestJsonClient {

  def passthrough(unencodedPath: String, methods: HttpMethod*): Route = {
    passthrough(Uri(unencodedPath), methods: _*)
  }

  // Danger: it is a common mistake to pass in a URI that omits the query parameters included in the original request to Orch.
  // To preserve the query, extract it and attach it to the passthrough URI using `.withQuery(query)`.
  def passthrough(uri: Uri, methods: HttpMethod*): Route = methods map { inMethod =>
    generateExternalHttpRequestForMethod(uri, inMethod)
  } reduce (_ ~ _)

  def encodeUri(path: String): String = FireCloudDirectiveUtils.encodeUri(path)

  private def generateExternalHttpRequestForMethod(uri: Uri, inMethod: HttpMethod) = {
    method(inMethod) { requestContext =>
      val outgoingRequest = requestContext.request
        .withUri(uri)
        .withHeaders(requestContext.request.headers.filter(
          hdr => FireCloudDirectiveUtils.allowedPassthroughHeaders.contains(hdr.lowercaseName())))
      requestContext.complete(unAuthedRequest(outgoingRequest)) //NOTE: This is actually AUTHED because we pass through the Authorization header
    }
  }

  def withResourceFileContents(path: String)(innerRoute: String => Route): Route =
    innerRoute( FileUtils.readAllTextFromResource(path) )

}
