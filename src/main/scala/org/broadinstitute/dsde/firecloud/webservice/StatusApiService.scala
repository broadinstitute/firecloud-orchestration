package org.broadinstitute.dsde.firecloud.webservice

import java.text.SimpleDateFormat

import org.broadinstitute.dsde.firecloud.service._
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport
import spray.json.{JsObject, JsString}
import spray.routing._
import spray.json.DefaultJsonProtocol._

object BuildTimeVersion {
  val version = Option(getClass.getPackage.getImplementationVersion)
  val versionJson = JsObject(Map("version" -> JsString(version.getOrElse("n/a"))))
}

trait StatusApiService extends HttpService with PerRequestCreator with FireCloudDirectives with SprayJsonSupport {

  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private implicit val executionContext = actorRefFactory.dispatcher

  val statusServiceConstructor: () => StatusService

  val statusRoutes: Route = {
    path("status") {
      get { requestContext =>
        perRequest(requestContext, StatusService.props(statusServiceConstructor), StatusService.CollectStatusInfo)
      }
    } ~
    path( "version") {
      get { requestContext =>
        requestContext.complete(StatusCodes.OK, BuildTimeVersion.versionJson)
      }
    }
  }

}
