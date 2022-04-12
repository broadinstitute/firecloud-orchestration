package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods.{GET, POST}
import akka.http.scaladsl.model.{FormData, StatusCodes}
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Route.seal
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec

import scala.concurrent.ExecutionContext

class Oauth2ApiServiceSpec extends BaseServiceSpec with Oauth2ApiService {
  override val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  "OAuth2ApiService" - {
    "authorization endpoint" - {
      "should redirect GET requests" in {
        Get("/oauth2/authorize?query1=foo&query2=bar") ~> seal(oauth2Routes) ~> check {
          handled should be(true)
          status should be(StatusCodes.Found)
          header[Location].map(_.value) should be(Some("https://accounts.google.com/o/oauth2/v2/auth?query1=foo&query2=bar"))
        }
      }
      "should reject non-GET requests" in {
        allHttpMethodsExcept(GET).foreach { method =>
          new RequestBuilder(method)("/oauth2/authorize?query1=foo&query2=bar") ~> seal(oauth2Routes) ~> check {
            handled should be(true)
            status should be(StatusCodes.MethodNotAllowed)
          }
        }
      }
    }
    "token endpoint" - {
      "should proxy POST requests" in {
        Post("/oauth2/token")
          .withEntity(FormData(
            "grant_type" -> "authorization_code",
            "code" -> "1234",
            "client_id" -> "some_client_id").toEntity) ~> seal(oauth2Routes) ~> check {
          handled should be(true)
        }
      }
      "should reject non-POST requests" in {
        allHttpMethodsExcept(POST).foreach { method =>
          new RequestBuilder(method)("/oauth2/token") ~> seal(oauth2Routes) ~> check {
            handled should be(true)
            status should be(StatusCodes.MethodNotAllowed)
          }
        }
      }
    }
  }
}
