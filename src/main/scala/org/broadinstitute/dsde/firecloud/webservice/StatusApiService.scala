package org.broadinstitute.dsde.firecloud.webservice

import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, MethodsService, PerRequestCreator}
import spray.json._
import spray.routing._

class StatusApiServiceActor extends Actor with StatusApiService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait StatusApiService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private final val ApiPrefix = "status"
  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private implicit val executionContext = actorRefFactory.dispatcher

  val routes: Route =
    pathPrefix(ApiPrefix) {
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
}
