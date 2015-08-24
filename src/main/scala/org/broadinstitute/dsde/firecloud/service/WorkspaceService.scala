package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.{Actor, Props}
import org.slf4j.LoggerFactory
import spray.client.pipelining.{Get, Post}
import spray.http.StatusCodes._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.routing._

import org.broadinstitute.dsde.vault.common.directives.OpenAMDirectives._
import org.broadinstitute.dsde.firecloud.{EntityClient, FireCloudConfig, HttpClient}

class WorkspaceServiceActor extends Actor with WorkspaceService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait WorkspaceService extends HttpService with FireCloudDirectives {

  private final val ApiPrefix = "workspaces"
  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)
  lazy val rawlsUrlRoot = FireCloudConfig.Rawls.baseUrl
  lazy val rawlsWorkspacesRoot = rawlsUrlRoot + "/workspaces"

  val routes: Route =
    pathPrefix(ApiPrefix) {
      pathEnd {
        get { requestContext =>
          actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
            HttpClient.PerformExternalRequest(Get(rawlsWorkspacesRoot))
        } ~
          post {
            entity(as[String]) { ingest =>
              commonNameFromOptionalCookie() { username => requestContext =>
                username match {
                  case Some(x) =>
                    val params = ingest.parseJson.convertTo[Map[String, JsValue]]
                      .updated("createdBy", username.get.toJson)
                      .updated("createdDate", dateFormat.format(new Date()).toJson)
                      .updated("attributes", JsObject())
                    val request = Post(
                      rawlsWorkspacesRoot,
                      HttpClient.createJsonHttpEntity(params.toJson.compactPrint)
                    )
                    actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
                      HttpClient.PerformExternalRequest(request)
                  case None =>
                    log.error("No authenticated username provided.")
                    requestContext.complete(Unauthorized)
                }
              }
            }
          }
      } ~
        pathPrefix(Segment / Segment) { (workspaceNamespace, workspaceName) =>
          pathEnd { requestContext =>
              actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
                HttpClient.PerformExternalRequest(Get(
                  rawlsWorkspacesRoot + "/%s/%s".format(workspaceNamespace, workspaceName)
                ))
          } ~
            path("methodconfigs") {
              get { requestContext =>
                actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
                  HttpClient.PerformExternalRequest(Get(
                    rawlsWorkspacesRoot + "/%s/%s/methodconfigs".format(workspaceNamespace, workspaceName)
                  ))
              }
            } ~
            path("importEntities") {
              post {
                formFields( 'entities ) { (entitiesTSV) =>
                  respondWithJSON { requestContext =>
                    actorRefFactory.actorOf(Props(new EntityClient(requestContext))) !
                      EntityClient.ImportEntitiesFromTSV(workspaceNamespace, workspaceName, entitiesTSV)
                  }
                }
              }
            }
        }
    }
}
