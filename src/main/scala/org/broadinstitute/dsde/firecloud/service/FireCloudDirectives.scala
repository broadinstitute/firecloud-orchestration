package org.broadinstitute.dsde.firecloud.service

import spray.http.{Uri, HttpMethod}
import spray.http.MediaTypes._

trait FireCloudDirectives extends spray.routing.Directives with PerRequestCreator with spray.httpx.RequestBuilding {
  def respondWithJSON = respondWithMediaType(`application/json`)

  def passthrough(unencodedPath: String, methods: HttpMethod*) = methods map { inMethod =>
    val outMethod = new RequestBuilder(inMethod)

    val path = encodeUri(unencodedPath)

    // POST, PUT, PATCH
    if (inMethod.isEntityAccepted) {
      method(inMethod) {
        respondWithJSON { requestContext =>
          externalHttpPerRequest(requestContext, outMethod(path, requestContext.request.entity))
        }
      }
    }
    else {
      // GET, DELETE
      method(inMethod) { requestContext =>
        externalHttpPerRequest(requestContext, outMethod(path))
      }
    }

  } reduce (_ ~ _)

  def passthroughAllPaths(ourEndpointPath: String, targetEndpointUrl: String) = pathPrefix(ourEndpointPath) {
    extract(_.request.method) { httpMethod =>
      unmatchedPath { remaining =>
        parameterMap { params =>
          passthrough(Uri(targetEndpointUrl + remaining).withQuery(params).toString, httpMethod)
        }
      }
    }
  }

  def encodeUri(path: String): String = {
    val pattern = """(https|http)://([^/\r\n]+)(/[^\r\n]*)?""".r

    def toUri(url: String) = url match {
      case pattern(theScheme, theHost, thePath) => Uri.from(scheme = theScheme, host = theHost, path = thePath)
    }

    toUri(path).toString
  }
}
