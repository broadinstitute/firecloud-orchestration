package org.broadinstitute.dsde.firecloud.service

import spray.http.{Uri, HttpMethod}
import spray.http.MediaTypes._

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

trait FireCloudDirectives extends spray.routing.Directives with PerRequestCreator with spray.httpx.RequestBuilding {
  def respondWithJSON = respondWithMediaType(`application/json`)

  def passthrough(unencodedPath: String, methods: HttpMethod*) = methods map { inMethod =>
    generateExternalHttpPerRequestForMethod(requestCompression = false, unencodedPath, inMethod)
  } reduce (_ ~ _)

  def passthrough(requestCompression: Boolean, unencodedPath: String, methods: HttpMethod*) = methods map { inMethod =>
    generateExternalHttpPerRequestForMethod(requestCompression, unencodedPath, inMethod)
  } reduce (_ ~ _)

  def passthroughAllPaths(ourEndpointPath: String, targetEndpointUrl: String, requestCompression: Boolean = false) = pathPrefix(ourEndpointPath) {
    extract(_.request.method) { httpMethod =>
      unmatchedPath { remaining =>
        parameterMap { params =>
          passthrough(requestCompression, Uri(encodeUri(targetEndpointUrl + remaining)).withQuery(params).toString, httpMethod)
        }
      }
    }
  }

  def encodeUri(path: String): String = FireCloudDirectiveUtils.encodeUri(path)

  private def generateExternalHttpPerRequestForMethod(requestCompression: Boolean, unencodedPath: String, inMethod: HttpMethod) = {
    val outMethod = new RequestBuilder(inMethod)
    val path = encodeUri(unencodedPath)
    // POST, PUT, PATCH
    if (inMethod.isEntityAccepted) {
      method(inMethod) {
        respondWithJSON { requestContext =>
          externalHttpPerRequest(requestCompression, requestContext, outMethod(path, requestContext.request.entity))
        }
      }
    }
    else {
      // GET, DELETE
      method(inMethod) { requestContext =>
        externalHttpPerRequest(requestCompression, requestContext, outMethod(path))
      }
    }
  }

}
