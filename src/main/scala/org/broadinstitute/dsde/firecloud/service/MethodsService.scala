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

  // Agora routes that require special handling

  val methodsACLOverrideRoute: Route =
    path("methods" / Segment / Segment / IntNumber / "permissions") { (namespace, name, snapshotId) =>

      // generate the Agora url we'll call
      val url = FireCloudConfig.Agora.methodsPermissionsFromMethod(namespace, name, snapshotId)

      delete {
        //this functionality is achieve via "NO ACCESS" in the Post
        requestContext => requestContext.complete(BadRequest)
      } ~
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
        } ~
        put {
          //use POST for all functionality
          requestContext => requestContext.complete(BadRequest)
        }}



  val configurationsACLOverrideRoute: Route =
    path("configurations" / Segment / Segment / IntNumber / "permissions") { (namespace, name, snapshotId) =>

      // generate the Agora url we'll call
      val url = FireCloudConfig.Agora.configurationsPermissionsFromConfig(namespace, name, snapshotId)

      delete {
        //this functionality is achieve via "NO ACCESS" in the Post
        requestContext => requestContext.complete(BadRequest)
      } ~
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
        } ~
        put {
          //use POST for all functionality
          requestContext => requestContext.complete(BadRequest)
        }}


      // Agora routes that can be passthroughs. Because these routes conflict with the override routes, make sure
      // they are processed second!
      val passthroughRoutes: Route =
        passthroughAllPaths("methods", FireCloudConfig.Agora.methodsBaseUrl) ~
          passthroughAllPaths("configurations", FireCloudConfig.Agora.configurationsBaseUrl)

      // combine all of the above route definitions, keeping overrides first.
      val routes =  methodsACLOverrideRoute ~ configurationsACLOverrideRoute ~ passthroughRoutes


}
