package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.MethodRepository._
import spray.routing.{HttpService, Route}
import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.core.{AgoraPermissionActor, AgoraPermissionHandler}
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





trait MethodsService extends HttpService with PerRequestCreator with FireCloudDirectives {

  lazy val log = LoggerFactory.getLogger(getClass)

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
          // take the body of the HTTP POST and construct a FireCloudPermission from it
          entity(as[List[FireCloudPermission]]) {
            fireCloudPermissions => requestContext =>
              // translate the FireCloudPermissions into  AgoraPermissions
              val agoraPermissions = fireCloudPermissions.map(x => x.toAgoraPermission)
              // pass to AgoraPermissionHandler
              perRequest(
                requestContext,
                Props(new AgoraPermissionActor(requestContext)),
                AgoraPermissionHandler.Post(url, agoraPermissions))
          }
        }}




      // Agora routes that can be passthroughs. Because these routes conflict with the override routes, make sure
      // they are processed second!
      val passthroughRoutes: Route =
        passthroughAllPaths("methods", FireCloudConfig.Agora.methodsBaseUrl) ~
          passthroughAllPaths("configurations", FireCloudConfig.Agora.configurationsBaseUrl)

      // combine all of the above route definitions, keeping overrides first.
      val routes =  methodsAndConfigsACLOverrideRoute ~ passthroughRoutes



  private def getUrlFromBasePath(agoraEntity: String, namespace: String, name: String, snapshotId: Int): String = {
    agoraEntity match {
      case "methods" =>
        FireCloudConfig.Agora.methodsPermissionsFromMethod(namespace, name, snapshotId)
      case "configurations" =>
        FireCloudConfig.Agora.configurationsPermissionsFromConfig(namespace, name, snapshotId)
    }

  }
}
