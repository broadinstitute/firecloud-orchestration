package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.HttpMethods.{GET, POST}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, RegisterService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.service.RegisterService.{samAcceptTosUrl, samTosTextUrl}

import scala.concurrent.ExecutionContext

trait RegisterApiService extends FireCloudDirectives with RequestBuilding with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext

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

  val tosRoutes: Route = {
    pathPrefix("tos") {
      path("text") {
        passthrough(samTosTextUrl, GET)
      }
    } ~
    pathPrefix("register") {
      path("termsofservice") {
        post {
          requireUserInfo() { userInfo =>
            passthrough(samAcceptTosUrl, POST)
          }
        }
      }
    }
  }
}
