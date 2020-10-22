package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ContentTypes, HttpMethod, Uri}
import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.dataaccess.DsdeHttpDAO
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
}

trait FireCloudDirectives extends Directives with RequestBuilding with DsdeHttpDAO {

  def passthrough(unencodedPath: String, methods: HttpMethod*): Route = {
    passthrough(Uri(unencodedPath), methods: _*)
  }

  // Danger: it is a common mistake to pass in a URI that omits the query parameters included in the original request to Orch.
  // To preserve the query, extract it and attach it to the passthrough URI using `.withQuery(query)`.
  def passthrough(uri: Uri, methods: HttpMethod*): Route = methods map { inMethod =>
    generateExternalHttpPerRequestForMethod(uri, inMethod)
  } reduce (_ ~ _)

  def encodeUri(path: String): String = FireCloudDirectiveUtils.encodeUri(path)

  private def generateExternalHttpPerRequestForMethod(uri: Uri, inMethod: HttpMethod) = {
    // POST, PUT, PATCH
    if (inMethod.isEntityAccepted) {
      method(inMethod) { requestContext =>
        val outMethod = new RequestBuilder(inMethod)(uri, requestContext.request.entity)
        requestContext.complete(executeRequestRawFromHttpRequest(outMethod))
      }
    }
    else {
      // GET, DELETE
      method(inMethod) { requestContext =>
        val outMethod = new RequestBuilder(inMethod)(uri)
        requestContext.complete(executeRequestRawFromHttpRequest(outMethod))
      }
    }
  }

  def withResourceFileContents(path: String)(innerRoute: String => Route): Route =
    innerRoute( FileUtils.readAllTextFromResource(path) )

}
