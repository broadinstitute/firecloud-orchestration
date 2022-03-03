package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Actor
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, StorageService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives

import scala.concurrent.ExecutionContext

trait StorageApiService extends FireCloudDirectives with StandardUserInfoDirectives {

  private final val ApiPrefix = "storage"
  implicit val executionContext: ExecutionContext

  val storageServiceConstructor: UserInfo => StorageService

  val storageRoutes: Route =
    pathPrefix("api") {
      pathPrefix(ApiPrefix) {
        path(Segment / Remaining) { (bucket, obj) =>
          requireUserInfo() { userInfo =>
            complete { storageServiceConstructor(userInfo).getObjectStats(bucket, obj) }
          }
        }
      }
    }
}
