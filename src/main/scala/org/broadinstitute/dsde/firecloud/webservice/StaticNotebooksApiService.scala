package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.{RestJsonClient, StandardUserInfoDirectives}

import scala.concurrent.ExecutionContext

trait StaticNotebooksApiService extends FireCloudDirectives with StandardUserInfoDirectives with RestJsonClient {

  implicit val executionContext: ExecutionContext

  val calhounStaticNotebooksRoot: String = FireCloudConfig.StaticNotebooks.baseUrl
  val calhounStaticNotebooksURL: String = s"$calhounStaticNotebooksRoot/api/convert"

  val staticNotebooksRoutes: Route = {
    path("staticNotebooks" / "convert") {
      requireUserInfo() { userInfo =>
        post {
          requestContext =>
            // call Calhoun and pass its response back to our own caller
            // can't use passthrough() here because that demands a JSON response

            //TODO: ensure mediatype is still honored, but respondWithMediaType was deprecated
            //because it was an anti-pattern

            val extReq = Post(calhounStaticNotebooksURL, requestContext.request.entity)
            userAuthedRequest(extReq)(userInfo).flatMap { resp =>
              requestContext.complete(resp)
            }
        }
      }
    }
  }
}
