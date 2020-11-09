package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.model.ShareLog.ShareType
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, ShareLogService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives

import scala.concurrent.ExecutionContext

trait ShareLogApiService extends FireCloudDirectives
  with StandardUserInfoDirectives with SprayJsonSupport {

  implicit val executionContext: ExecutionContext
  val shareLogServiceConstructor: () => ShareLogService

  val shareLogServiceRoutes: Route = {
    pathPrefix("sharelog") {
      path("sharees" ) {
        get {
          parameter("shareType".?) { shareType =>
            requireUserInfo() { userInfo =>
              complete { shareLogServiceConstructor().GetSharees(userInfo.id, shareType.map(ShareType.withName)) }
            }
          }
        }
      }
    }
  }

}
