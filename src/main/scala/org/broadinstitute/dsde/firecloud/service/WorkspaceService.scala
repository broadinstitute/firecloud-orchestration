package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.{Actor, Props}
import org.slf4j.LoggerFactory
import spray.client.pipelining.Post
import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.routing._

import org.broadinstitute.dsde.firecloud.{EntityClient, FireCloudConfig, HttpClient}

class WorkspaceServiceActor extends Actor with WorkspaceService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait WorkspaceService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private final val ApiPrefix = "workspaces"
  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)
  lazy val rawlsUrlRoot = FireCloudConfig.Rawls.baseUrl
  lazy val rawlsWorkspacesRoot = rawlsUrlRoot + "/workspaces"

  val routes: Route =
    pathPrefix(ApiPrefix) {
      pathEnd {
        passthrough(rawlsWorkspacesRoot, HttpMethods.GET) ~
        post {
          entity(as[String]) { ingest =>
            // TODO: replace with a directive that pulls the username from the Google info!
            // TODO: rawls should populate createdBy/createdDate/attributes on its own!
            // commonNameFromOptionalCookie() { username => requestContext =>
            requestContext =>
              val username = Option("FIXME!")

              username match {
                case Some(x) =>
                  val params = ingest.parseJson.convertTo[Map[String, JsValue]]
                    .updated("createdBy", username.get.toJson)
                    .updated("createdDate", dateFormat.format(new Date()).toJson)
                    .updated("attributes", JsObject())
                  val extReq = Post(
                    rawlsWorkspacesRoot,
                    HttpClient.createJsonHttpEntity(params.toJson.compactPrint)
                  )
                  externalHttpPerRequest(requestContext, extReq)
                case None =>
                  log.error("No authenticated username provided.")
                  requestContext.complete(Unauthorized)
              }
            // }
          }
        }
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
        }
      }
    }
}
