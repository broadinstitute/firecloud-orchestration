package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.{AgoraPermissionActor, AgoraPermissionHandler}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.MethodRepository._
import org.slf4j.LoggerFactory
import spray.routing.{HttpService, Route}
import spray.httpx.SprayJsonSupport._



class MethodsServiceActor extends Actor with MethodsService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait MethodsService extends HttpService with PerRequestCreator with FireCloudDirectives {

  lazy val log = LoggerFactory.getLogger(getClass)

  // Agora routes that require special handling
  val overrideRoutes: Route =
    path("configurations" / Segment / Segment / IntNumber / "permissions") { (namespace, name, snapshotId) =>

      // generate the Agora url we'll call
      val url = FireCloudConfig.Agora.methodsPermissionsFromMethod(namespace, name, snapshotId)

      delete {
        // extract "user" from the url
        parameter("user") { user => requestContext =>
          // pass the user to AgoraPermissionHandler
          perRequest(requestContext, Props(new AgoraPermissionActor(requestContext)),
            AgoraPermissionHandler.Delete(url, user))
        }
      } ~
      get { requestContext =>
        // pass to AgoraPermissionHandler
        perRequest(requestContext, Props(new AgoraPermissionActor(requestContext)),
          AgoraPermissionHandler.Get(url))
      } ~
      post {
        // take the body of the HTTP POST and construct a FireCloudPermission from it
        entity(as[FireCloudPermission]) { fireCloudPermission => requestContext =>
          // translate the FireCloudPermission into an AgoraPermission
          val agoraPermission = fireCloudPermission.toAgoraPermission
          // pass the agora permission to AgoraPermissionHandler
          perRequest(requestContext, Props(new AgoraPermissionActor(requestContext)),
            AgoraPermissionHandler.Post(url, agoraPermission))
        }
      }
      // TODO: handle put requests
    }
    // TODO: all of the above, but for methods instead of configurations

  // Agora routes that can be passthroughs. Because these routes conflict with the override routes, make sure
  // they are processed second!
  val passthroughRoutes: Route =
    passthroughAllPaths("methods", FireCloudConfig.Agora.methodsBaseUrl) ~
    passthroughAllPaths("configurations", FireCloudConfig.Agora.configurationsBaseUrl)

  // combine all of the above route definitions, keeping overrides first.
  val routes = overrideRoutes ~ passthroughRoutes

}
