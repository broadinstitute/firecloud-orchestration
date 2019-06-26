package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.routing._
import spray.http.MediaTypes.`text/html`

trait StaticNotebooksApiService extends HttpService
  with FireCloudDirectives
  with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher

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
