package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives

import scala.concurrent.ExecutionContext

trait StaticNotebooksApiService extends FireCloudDirectives with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext

  val calhounStaticNotebooksRoot: String = FireCloudConfig.StaticNotebooks.baseUrl
  val calhounStaticNotebooksURL: String = s"$calhounStaticNotebooksRoot/api/convert"

  val staticNotebooksRoutes: Route = {
    path("staticNotebooks" / "convert") {
      requireUserInfo() { _ =>
        post {
          respondWithMediaType(`text/html`) {
            requestContext =>
              // call Calhoun and pass its response back to our own caller
              // can't use passthrough() here because that demands a JSON response
              externalHttpPerRequest(requestContext,
                Post(calhounStaticNotebooksURL, requestContext.request.entity))
          }
        }
      }
    }
  }
}
