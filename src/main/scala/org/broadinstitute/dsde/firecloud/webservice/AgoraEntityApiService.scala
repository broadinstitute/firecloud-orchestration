package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.model.MethodRepository.EditMethodRequest
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impEditMethodRequest
import org.broadinstitute.dsde.firecloud.service.{AgoraEntityService, FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.firecloud.model.UserInfo
import spray.httpx.SprayJsonSupport._
import spray.routing.{HttpService, Route}

import scala.concurrent.ExecutionContext

trait AgoraEntityApiService extends HttpService with FireCloudRequestBuilding with FireCloudDirectives
  with StandardUserInfoDirectives{

  private implicit val ec: ExecutionContext = actorRefFactory.dispatcher

  val agoraEntityServiceConstructor: UserInfo => AgoraEntityService

  val agoraEntityRoutes: Route = {
    pathPrefix("api" / "methods" / "edit") {
      requireUserInfo() { userInfo =>
        entity(as[EditMethodRequest]) { editMethodRequest =>
          post { requestContext =>
            perRequest(requestContext,
              AgoraEntityService.props(agoraEntityServiceConstructor, userInfo),
              AgoraEntityService.EditMethod(editMethodRequest))
          }
        }
      }
    }
  }
}
