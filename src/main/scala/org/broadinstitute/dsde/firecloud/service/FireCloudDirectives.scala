package org.broadinstitute.dsde.firecloud.service

import spray.http.HttpMethod
import spray.http.MediaTypes._

trait FireCloudDirectives extends spray.routing.Directives with PerRequestCreator with spray.httpx.RequestBuilding {
  def respondWithJSON = respondWithMediaType(`application/json`)

  def passthrough(path: String, methods: HttpMethod*) = methods map { inMethod =>
    val outMethod = new RequestBuilder(inMethod)

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
}
