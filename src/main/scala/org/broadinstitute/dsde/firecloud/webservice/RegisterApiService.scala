package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.client.RequestBuilding
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, RegisterService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.server.{Directives, Route}

import scala.concurrent.ExecutionContext

trait RegisterApiService extends Directives with RequestBuilding with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext
  private lazy val log = LoggerFactory.getLogger(getClass)

  val registerServiceConstructor: () => RegisterService

  val registerRoutes: Route =
    pathPrefix("register") {
      path("profile") {
        post {
          requireUserInfo() { userInfo =>
            entity(as[BasicProfile]) { basicProfile =>
              complete { registerServiceConstructor().createUpdateProfile(userInfo, basicProfile) }
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
              complete { registerServiceConstructor().updateProfilePreferences(userInfo, preferences) }
            }
          }
        }
      }
    }
}
