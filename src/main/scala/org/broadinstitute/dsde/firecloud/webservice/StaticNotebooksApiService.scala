package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.http.HttpMethods
import spray.routing._

trait StaticNotebooksApiService extends HttpService
  with FireCloudDirectives
  with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher

  val calhounStaticNotebooksRoot: String = FireCloudConfig.StaticNotebooks.baseUrl

  val staticNotebooksRoutes: Route = {
    path("staticNotebooks" / "convert") {
      requireUserInfo() { _ =>
        post {
          passthrough(s"$calhounStaticNotebooksRoot/api/convert", HttpMethods.POST)
        }
      }
    }
  }
}
