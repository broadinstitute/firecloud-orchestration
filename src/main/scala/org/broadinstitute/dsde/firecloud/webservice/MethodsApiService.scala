package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.{HttpMethods, Uri}
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{AgoraPermissionService, FireCloudDirectives}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

trait MethodsApiServiceUrls {
  val remoteMethodsPath = FireCloudConfig.Agora.authPrefix + "/methods"
  val remoteMethodsUrl = FireCloudConfig.Agora.baseUrl + remoteMethodsPath
  val remoteConfigurationsPath = FireCloudConfig.Agora.authPrefix + "/configurations"
  val remoteConfigurationsUrl = FireCloudConfig.Agora.baseUrl + remoteConfigurationsPath
  val remoteMultiPermissionsUrl = remoteMethodsUrl + "/permissions"

  val localMethodsPath = "methods"
  val localConfigsPath = "configurations"
}

trait MethodsApiService extends MethodsApiServiceUrls with FireCloudDirectives with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext

  val agoraPermissionService: UserInfo => AgoraPermissionService

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
            extract(_.request.uri.query()) { query =>
              // only pass query params for GETs
              val targetUri = if (method == HttpMethods.GET)
                Uri(passthroughBase).withQuery(query)
              else
                Uri(passthroughBase)
              passthrough(targetUri, method)
            }
          }
        }
      } ~
        pathPrefix( Segment / Segment / IntNumber ) { (namespace, name, snapshotId) =>
          pathEnd {
            (get | delete) {
              extract(_.request.method) { method =>
                extract(_.request.uri.query()) { query =>
                  // only pass query params for GETs
                  val baseUri = Uri(s"$passthroughBase/${urlify(namespace, name)}/$snapshotId")
                  val targetUri = if (method == HttpMethods.GET)
                    baseUri.withQuery(query)
                  else
                    baseUri
                  passthrough(targetUri, method)
                }
              }
            }
          } ~
            path( "permissions") {
              val url = s"$passthroughBase/${urlify(namespace, name)}/$snapshotId/permissions"
              get {
                requireUserInfo() { userInfo =>
                  // pass to AgoraPermissionHandler
                  complete { agoraPermissionService(userInfo).getAgoraPermission(url) }
                }
              } ~
                post {
                  // explicitly pull in the json-extraction error handler from ModelJsonProtocol
                  handleRejections(entityExtractionRejectionHandler) {
                    // take the body of the HTTP POST and construct a FireCloudPermission from it
                    entity(as[List[FireCloudPermission]]) { fireCloudPermissions =>
                      requireUserInfo() { userInfo =>
                        complete {
                          agoraPermissionService(userInfo).createAgoraPermission(url, fireCloudPermissions.map(_.toAgoraPermission))
                        }
                      }
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
                    val agoraPermissions = fireCloudPermissions map { fc =>
                      EntityAccessControlAgora(Method(fc.method), fc.acls.map(_.toAgoraPermission))
                    }
                    requireUserInfo() { userInfo =>
                      complete { agoraPermissionService(userInfo).batchInsertAgoraPermissions(agoraPermissions) }
                    }
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
              pathPrefix( IntNumber ) { snapshotId =>
                pathEnd {
                  post {
                    extract(_.request.uri.query()) { query =>
                      passthrough(Uri(s"$passthroughBase/${urlify(namespace, name)}/$snapshotId").withQuery(query), HttpMethods.POST)
                    }
                  }
                } ~
                  path( "configurations" ) {
                    get {
                      passthrough(s"$passthroughBase/${urlify(namespace,name)}/$snapshotId/configurations", HttpMethods.GET)
                    }
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
