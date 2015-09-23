package org.broadinstitute.dsde.firecloud.service

import spray.http.MediaTypes._
import spray.client.pipelining._

trait FireCloudDirectives extends spray.routing.Directives with PerRequestCreator {
  def respondWithJSON = respondWithMediaType(`application/json`)

  def passthrough(path: String, methods: String*) = methods map {
    case "delete" => delete { requestContext =>
      externalHttpPerRequest(requestContext, Delete(path))
    }
    case "get" => get { requestContext =>
     externalHttpPerRequest(requestContext, Get(path))
    }
    case "patch" => patch {
      respondWithJSON { requestContext =>
        externalHttpPerRequest(requestContext, Patch(path, requestContext.request.entity))
      }
    }
    case "post" => post {
      respondWithJSON { requestContext =>
        externalHttpPerRequest(requestContext, Post(path, requestContext.request.entity))
      }
    }
    case "put" => put {
      respondWithJSON { requestContext =>
        externalHttpPerRequest(requestContext, Put(path, requestContext.request.entity))
      }
    }
  } reduce (_ ~ _)
}
