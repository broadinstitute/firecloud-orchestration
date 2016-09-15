package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.slf4j.LoggerFactory
import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.json._
import spray.routing._

trait LibraryService extends HttpService with FireCloudDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  lazy val rawlsCuratorUrl = FireCloudConfig.Rawls.authUrl + "/user/role/curator"

  val routes: Route =
    pathPrefix("schemas") {
      path("library-attributedefinitions-v1") {
        respondWithJSON {
          withResourceFileContents("library/attribute-definitions.json") { jsonContents =>
            complete(OK, jsonContents)
          }
        }
      }
    } ~
    pathPrefix("api") {
      pathPrefix("library") {
        path("user" / "role" / "curator") {
          passthrough(rawlsCuratorUrl, HttpMethods.GET)
        }
      }
    }

}
