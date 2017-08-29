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
  val remotePermissionsTemplate = FireCloudConfig.Agora.baseUrl + FireCloudConfig.Agora.authPrefix + "/%s/%s/%s/%s/permissions"
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
              passthrough(s"$passthroughBase/$namespace/$name/$snapshotId", method)
            }
          }
        } ~
        path( "permissions") {
          val url = getUrlFromBasePath(agoraEntityType, namespace, name, snapshotId)
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
            passthrough(s"$passthroughBase/$namespace/$name/configurations", HttpMethods.GET)
          }
        } ~
        path( IntNumber ) { snapshotId =>
          post {
            passthrough(s"$passthroughBase/$namespace/$name/$snapshotId", HttpMethods.POST)
          }
        }
      }
    }

  private def getUrlFromBasePath(agoraEntity: String, namespace: String, name: String, snapshotId: Int): String = {
    remotePermissionsTemplate.format(agoraEntity, namespace, name, snapshotId)
  }
}
