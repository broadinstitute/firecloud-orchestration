package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, RegisterService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.server.Route

import scala.concurrent.ExecutionContext

trait RegisterApiService extends FireCloudDirectives with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext
  private lazy val log = LoggerFactory.getLogger(getClass)

  val registerServiceConstructor: () => RegisterService

  val registerRoutes: Route =
    pathPrefix("register") {
      path("profile") {
        post {
          requireUserInfo() { userInfo =>
            entity(as[BasicProfile]) { basicProfile =>
              complete { registerServiceConstructor().CreateUpdateProfile(userInfo, basicProfile) }
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
            entity(as[Map[String, String]]) { preferences =>
              complete { registerServiceConstructor().UpdateProfilePreferences(userInfo, preferences) }
            }
          }
        }
      }
    }
}
