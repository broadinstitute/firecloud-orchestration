package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{StorageService, WorkspaceService, FireCloudDirectives, PerRequestCreator}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.routing._

trait StorageApiService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private final val ApiPrefix = "storage"
  private implicit val executionContext = actorRefFactory.dispatcher

  val storageServiceConstructor: UserInfo => StorageService

  val storageRoutes: Route =
    requireUserInfo() { userInfo =>
      pathPrefix("api") {
        pathPrefix(ApiPrefix) {
          // call Google's storage REST API for info about this object
          path(Segment / Rest) { (bucket, obj) => requestContext =>
            perRequest(requestContext,
              StorageService.props(storageServiceConstructor, userInfo),
              StorageService.GetObjectStats(bucket, obj))
          }
        }
      }
    }
}
