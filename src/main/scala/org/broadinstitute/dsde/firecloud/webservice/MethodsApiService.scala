package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.{AgoraPermissionActor, AgoraPermissionHandler}
import org.broadinstitute.dsde.firecloud.model.MethodRepository._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, PerRequestCreator}
import spray.http.{HttpMethods, Uri}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing.{HttpService, Route}

trait MethodsApiServiceUrls {
  val remoteMethodsPath = FireCloudConfig.Agora.authPrefix + "/methods"
  val remoteMethodsUrl = FireCloudConfig.Agora.baseUrl + remoteMethodsPath
  val remoteConfigurationsPath = FireCloudConfig.Agora.authPrefix + "/configurations"
  val remoteConfigurationsUrl = FireCloudConfig.Agora.baseUrl + remoteConfigurationsPath
  val remoteMultiPermissionsUrl = remoteMethodsUrl + "/permissions"

  val localMethodsPath = "methods"
  val localConfigsPath = "configurations"
}

trait MethodsApiService extends HttpService
  with PerRequestCreator with FireCloudDirectives with MethodsApiServiceUrls {

  val methodsApiServiceRoutes: Route =
  // routes that are valid for both configurations and methods
    pathPrefix( "configurations|methods".r ) { agoraEntityType =>

      val passthroughBase = agoraEntityType match {
        case "methods" => remoteMethodsUrl
        case "configurations" => remoteConfigurationsUrl
      }

      pathEnd {
        (get | post) {
          extract(_.request.method) { method =>
            extract(_.request.uri.query) { query =>
              passthrough(Uri(passthroughBase).withQuery(query), method)
            }
          }
        }
      } ~
      pathPrefix( Segment / Segment / IntNumber ) { (namespace, name, snapshotId) =>
        pathEnd {
          (get | delete) {
            extract(_.request.method) { method =>
              passthrough(s"$passthroughBase/${urlify(namespace,name)}/$snapshotId", method)
            }
          }
        } ~
        path( "permissions") {
          val url = s"$passthroughBase/${urlify(namespace,name)}/$snapshotId/permissions"
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
            }
          }
        }
      }
    } ~
    // routes that are only valid for methods
    pathPrefix( "methods" ) {
      val passthroughBase = remoteMethodsUrl
      path( "definitions" ) {
        get {
          passthrough(s"$passthroughBase/definitions", HttpMethods.GET)
        }
      } ~
      path( "permissions") {
        put {
          handleRejections(entityExtractionRejectionHandler) {
            entity(as[List[MethodAclPair]]) { fireCloudPermissions =>
              requestContext =>
                val agoraPermissions = fireCloudPermissions map { fc =>
                  EntityAccessControlAgora(Method(fc.method), fc.acls.map(_.toAgoraPermission))
                }
                perRequest(
                  requestContext,
                  Props(new AgoraPermissionActor(requestContext)),
                  AgoraPermissionHandler.MultiUpsert(agoraPermissions))
            }
          }
        }
      } ~
      pathPrefix( Segment / Segment ) { (namespace, name) =>
        path( "configurations" ) {
          get {
            passthrough(s"$passthroughBase/${urlify(namespace,name)}/configurations", HttpMethods.GET)
          }
        } ~
        path( IntNumber ) { snapshotId =>
          post {
            passthrough(s"$passthroughBase/${urlify(namespace,name)}/$snapshotId", HttpMethods.POST)
          }
        }
      }
    }

  /* special handling of url encoding for agora entity namespace/name here.
      some entities were created with spaces or other special characters in their name
      before we added syntax validation. This special handling covers those entities -
      though it's largely untested and there may still be problems. Any entities created
      after syntax validation should be just fine and the encoding won't touch them.
   */
  private def urlify(namespace:String, name:String) = enc(namespace) + "/" + enc(name)
  private def enc(in:String) = java.net.URLEncoder.encode(in,"utf-8").replace("+", "%20")

}
