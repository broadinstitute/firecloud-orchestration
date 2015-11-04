package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.MethodRepository._
import spray.http.StatusCodes
import spray.routing.{RejectionHandler, MalformedRequestContentRejection, HttpService, Route}
import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.core.{AgoraPermissionActor, AgoraPermissionHandler}
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, HttpResponseWithErrorReport, ErrorReport}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.{FireCloudConfig}
import org.slf4j.{ LoggerFactory}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

class MethodsServiceActor extends Actor with MethodsService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

object MethodsService {
  val remoteMethodsPath = FireCloudConfig.Agora.authPrefix + "/methods"
  val remoteMethodsUrl = FireCloudConfig.Agora.baseUrl + remoteMethodsPath
  val remoteConfigurationsPath = FireCloudConfig.Agora.authPrefix + "/configurations"
  val remoteConfigurationsUrl = FireCloudConfig.Agora.baseUrl + remoteConfigurationsPath
  val remotePermissionsTemplate = FireCloudConfig.Agora.baseUrl + FireCloudConfig.Agora.authPrefix + "/%s/%s/%s/%s/permissions"
}

trait MethodsService extends HttpService with PerRequestCreator with FireCloudDirectives {

  lazy val log = LoggerFactory.getLogger(getClass)

  val localMethodsPath = "methods"
  val localConfigsPath = "configurations"

  // Agora permissions that require special handling
  val methodsAndConfigsACLOverrideRoute: Route =
    path( "configurations|methods".r / Segment / Segment / IntNumber / "permissions") { ( agora_ent, namespace, name, snapshotId) =>
      val url = getUrlFromBasePath(agora_ent, namespace, name, snapshotId)
      get { requestContext =>
        // pass to AgoraPermissionHandler
        perRequest(requestContext,
          Props(new AgoraPermissionActor(requestContext)),
          AgoraPermissionHandler.Get(url))
      } ~
      post {
        // explicitly pull in the json-extraction error handler from ModelJsonProtocol
        handleRejections(entityExtractionRejectionHandler) {
          // take the body of the HTTP POST and construct a FireCloudPermission from it
          entity(as[List[FireCloudPermission]]) {
            fireCloudPermissions => requestContext =>
              perRequest(
                requestContext,
                Props(new AgoraPermissionActor(requestContext)),
                AgoraPermissionHandler.Post(url, fireCloudPermissions.map(_.toAgoraPermission)))
          }
        } ~
        delete {
          complete(StatusCodes.MethodNotAllowed)
        } ~
        put {
          complete(StatusCodes.MethodNotAllowed)
        }
      } ~
      delete {
        complete(StatusCodes.MethodNotAllowed)
      } ~
      put {
        complete(StatusCodes.MethodNotAllowed)
      }
    }

  // Agora routes that can be passthroughs. Because these routes conflict with the override routes, make sure
  // they are processed second!
  val passthroughRoutes: Route =
    passthroughAllPaths(localMethodsPath, MethodsService.remoteMethodsUrl) ~
      passthroughAllPaths(localConfigsPath, MethodsService.remoteConfigurationsUrl)

  // combine all of the above route definitions, keeping overrides first.
  val routes =  methodsAndConfigsACLOverrideRoute ~ passthroughRoutes


  private def getUrlFromBasePath(agoraEntity: String, namespace: String, name: String, snapshotId: Int): String = {
    MethodsService.remotePermissionsTemplate.format(agoraEntity, namespace, name, snapshotId)
  }
}
