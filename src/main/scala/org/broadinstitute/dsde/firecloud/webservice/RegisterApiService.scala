package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.Props
import authentikat.jwt._
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, PerRequestCreator, RegisterService}
import org.broadinstitute.dsde.firecloud.utils.{DateUtils, StandardUserInfoDirectives}
import org.slf4j.LoggerFactory
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing._

trait RegisterApiService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  private lazy val log = LoggerFactory.getLogger(getClass)

  val registerServiceConstructor: () => RegisterService

  val registerRoutes: Route =
    pathPrefix("register") {
      path("profile") {
        post {
          requireUserInfo() { userInfo =>
            entity(as[BasicProfile]) { basicProfile => requestContext =>
              perRequest(requestContext, RegisterService.props(registerServiceConstructor),
                RegisterService.CreateUpdateProfile(userInfo, basicProfile)
              )
            }
          }
        }
      }
    }

  val profileRoutes: Route =
    pathPrefix("profile") {
      path("preferences") {
        post {
          requireUserInfo() { userInfo =>
            entity(as[Map[String, String]]) { preferences => requestContext =>
              perRequest(requestContext, RegisterService.props(registerServiceConstructor),
                RegisterService.UpdateProfilePreferences(userInfo, preferences)
              )
            }
          }
        }
      }
    }
}
