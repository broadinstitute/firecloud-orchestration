package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{StorageService, FireCloudDirectives, PerRequestCreator}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.routing._

trait StorageApiService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private final val ApiPrefix = "storage"
  private implicit val executionContext = actorRefFactory.dispatcher

  val storageServiceConstructor: UserInfo => StorageService

  val storageRoutes: Route =
    pathPrefix("api") {
      pathPrefix(ApiPrefix) {
        path(Segment / Rest) { (bucket, obj) =>
          requireUserInfo() { userInfo =>
            requestContext =>
              perRequest(requestContext,
                StorageService.props(storageServiceConstructor, userInfo),
                StorageService.GetObjectStats(bucket, obj))
          }
        }
      }
    }
}
