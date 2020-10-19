package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.dataaccess.MockThurloeDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, RegisterService}
import akka.http.scaladsl.model.StatusCodes.{BadRequest, NoContent}
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

final class RegisterApiServiceSpec(override val executionContext: ExecutionContext) extends BaseServiceSpec with RegisterApiService
  with DefaultJsonProtocol with SprayJsonSupport {

//  def actorRefFactory = system

  override val registerServiceConstructor:() => RegisterService =
    RegisterService.constructor(app.copy(thurloeDAO = new RegisterApiServiceSpecThurloeDAO))

  "RegisterApiService" - {
    "update-preferences API" - {

      "should succeed with a single notifications key" in {
        val payload = Map(
          "notifications/foo" -> "yes"
        )
        assertPreferencesUpdate(payload, NoContent)
      }

      "should succeed with multiple notifications keys" in {
        val payload = Map(
          "notifications/foo" -> "yes",
          "notifications/bar" ->  "no",
          "notifications/baz" -> "astring"
        )
        assertPreferencesUpdate(payload, NoContent)
      }

      "should refuse with a single non-whitelisted key" in {
        val payload = Map(
          "abadkey" -> "astring"
        )
        assertPreferencesUpdate(payload, BadRequest)
      }

      "should refuse with mixed notifications and non-whitelisted keys" in {
        val payload = Map(
          "notifications/foo" -> "yes",
          "notifications/bar" ->  "no",
          "secretsomething" -> "astring"
        )
        assertPreferencesUpdate(payload, BadRequest)
      }

      "should refuse with the string 'notifications/' in the middle of a key" in {
        val payload = Map(
          "notifications/foo" -> "yes",
          "notifications/bar" ->  "no",
          "substring/notifications/arebad" -> "true"
        )
        assertPreferencesUpdate(payload, BadRequest)
      }

      "should succeed with an empty payload" in {
        val payload = Map.empty[String, String]
        assertPreferencesUpdate(payload, NoContent)
      }

    }
  }

  private def assertPreferencesUpdate(payload: Map[String, String], expectedStatus: StatusCode): Unit = {
    Post("/profile/preferences", payload) ~> dummyUserIdHeaders("RegisterApiServiceSpec") ~> sealRoute(profileRoutes) ~> check {
      status should be(expectedStatus)
    }
  }

  // for purposes of these tests, we treat Thurloe as if it is always successful.
  final class RegisterApiServiceSpecThurloeDAO extends MockThurloeDAO {
    override def saveKeyValues(userInfo: UserInfo, keyValues: Map[String, String])= Future.successful(Success(()))
  }

}

