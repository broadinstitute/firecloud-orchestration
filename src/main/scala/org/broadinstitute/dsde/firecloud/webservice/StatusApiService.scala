package org.broadinstitute.dsde.firecloud.webservice

import java.text.SimpleDateFormat
import java.util.Date

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service._
import spray.json._
import spray.routing._

trait StatusApiService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private implicit val executionContext = actorRefFactory.dispatcher

  val statusServiceConstructor: () => StatusService

  // Routes under the /api/ path require authentication, others do not
  val apiStatusRoutes: Route =
    pathPrefix("status") {
      pathEnd {
        respondWithJSON {
          complete {
            JsObject(
              "workspacesUrl" -> JsString(FireCloudConfig.Rawls.baseUrl + "/workspaces"),
              "methodsUrl" -> JsString(MethodsService.remoteMethodsUrl),
              "timestamp" -> JsString(dateFormat.format(new Date()))
            ).toString
          }
        }
      } ~ path("ping") {
        complete {
          dateFormat.format(new Date())
        }
      }
    }

  val publicStatusRoutes: Route = {
    path("status") {
      requestContext =>
        perRequest(requestContext, StatusService.props(statusServiceConstructor), StatusService.CollectStatusInfo)
    }
  }


}
