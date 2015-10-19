package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.{EntityClient, FireCloudConfig}
import org.slf4j.LoggerFactory
import spray.http.HttpMethods
import spray.routing._

class WorkspaceServiceActor extends Actor with WorkspaceService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait WorkspaceService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)
  lazy val rawlsWorkspacesRoot = FireCloudConfig.Rawls.workspacesUrl

  val routes: Route =
    pathPrefix("workspaces") {
      pathEnd {
        passthrough(rawlsWorkspacesRoot, HttpMethods.GET, HttpMethods.POST)
     } ~
      pathPrefix(Segment / Segment) { (workspaceNamespace, workspaceName) =>
        val workspacePath = rawlsWorkspacesRoot + "/%s/%s".format(workspaceNamespace, workspaceName)
        pathEnd {
          passthrough(workspacePath, HttpMethods.GET, HttpMethods.DELETE)
        } ~
        path("methodconfigs") {
          passthrough(workspacePath + "/methodconfigs", HttpMethods.GET, HttpMethods.POST)
        } ~
        path("importEntities") {
          post {
            formFields( 'entities ) { entitiesTSV =>
              respondWithJSON { requestContext =>
                perRequest(requestContext, Props(new EntityClient(requestContext)),
                  EntityClient.ImportEntitiesFromTSV(workspaceNamespace, workspaceName, entitiesTSV))
              }
            }
          }
        } ~
        path("updateAttributes") {
          passthrough(workspacePath, HttpMethods.PATCH)
        } ~
        path("acl") {
          passthrough(workspacePath + "/acl", HttpMethods.GET, HttpMethods.PATCH)
        } ~
        path("clone") {
          passthrough(workspacePath + "/clone", HttpMethods.POST)
        } ~
        path ("lock") {
          passthrough(workspacePath + "/lock", HttpMethods.PUT)
        } ~
        path ("unlock") {
          passthrough(workspacePath + "/unlock", HttpMethods.PUT)
        }
      }
    }
}
