package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.Actor
import spray.json._

import spray.routing._


import org.broadinstitute.dsde.firecloud.FireCloudConfig

class StatusServiceActor extends Actor with StatusService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait StatusService extends HttpService with PerRequestCreator with FireCloudDirectives {

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
