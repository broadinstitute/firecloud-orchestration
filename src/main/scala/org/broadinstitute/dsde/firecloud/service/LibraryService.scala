package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.Curator
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impCurator
import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.client.pipelining._
import spray.routing._

trait LibraryService extends HttpService with FireCloudDirectives with FireCloudRequestBuilding {

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
        path("user" / "role" / "curator") { requestContext =>
          val pipeline = authHeaders(requestContext) ~> sendReceive
          pipeline{Get(rawlsCuratorUrl)} map { response =>
            response.status match {
              case OK => requestContext.complete(OK, Curator(true))
              case NotFound => requestContext.complete(OK, Curator(false))
              case _ => requestContext.complete(response) // replay the root exception
            }
          }
        }
      }
    }

}
