package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.testkit.TestActorRef
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.{MockImportServiceDAO, MockResearchPurposeSupport, MockShareLogDAO}
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.{ProfileWrapper, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.Await
import scala.concurrent.duration._


class UserServiceSpec  extends BaseServiceSpec with BeforeAndAfterEach {

  val customApp = Application(agoraDao, new MockGoogleServicesFailedGroupsDAO(), ontologyDao, new MockRawlsDeleteWSDAO(), samDao, new MockSearchDeleteWSDAO(), new MockResearchPurposeSupport, thurloeDao, new MockShareLogDAO, new MockImportServiceDAO, shibbolethDao)

  val userServiceConstructor: (UserInfo) => UserService = UserService.constructor(customApp)

  val userToken: UserInfo = UserInfo("me@me.com", OAuth2BearerToken(""), 3600, "111")

  lazy val userService: UserService = userServiceConstructor(userToken)

  "UserService " - {
    "getNewAnonymousGroupName" - { // this indirectly tests getWord
      "should form a proper email address" in {
        val anonymousGroupName = userService.getNewAnonymousGroupName
        anonymousGroupName should endWith(FireCloudConfig.FireCloud.supportDomain)
        anonymousGroupName should startWith(FireCloudConfig.FireCloud.supportPrefix)
        anonymousGroupName should include("@")
      }
      "should return a different email address every time it's called" in {
        val anonymousGroupName1 = userService.getNewAnonymousGroupName
        val anonymousGroupName2 = userService.getNewAnonymousGroupName
        anonymousGroupName1 shouldNot include(anonymousGroupName2)
      }
    }
    "setUpAnonymizedGoogleGroup" - {
      "should return original keys if Google group creation fails" in {
        val keys = ProfileWrapper(userToken.id, List())
        val anonymousGroupName = "makeGoogleGroupCreationFail"
        val rqComplete = Await.
          result(userService.setupAnonymizedGoogleGroup(keys, anonymousGroupName), 3.seconds).
          asInstanceOf[RequestComplete[ProfileWrapper]]
        val returnedKeys = rqComplete.response
        returnedKeys should equal(keys)
      }
      "should return original keys if adding a member to Google group fails" in {
        val keys = ProfileWrapper(userToken.id, List())
        val anonymousGroupName = "makeAddMemberFail"
        val rqComplete = Await.
          result(userService.setupAnonymizedGoogleGroup(keys, anonymousGroupName), 3.seconds).
          asInstanceOf[RequestComplete[ProfileWrapper]]
        val returnedKeys = rqComplete.response
        returnedKeys should equal(keys)
      }
    }
  }
}

/*
 * Mock out DAO classes specific to this test class.
 * Override the chain of methods that are called within these service tests to isolate functionality.
 * [Copied/modified from WorkspaceServiceSpec]
 */
class MockGoogleServicesFailedGroupsDAO extends MockGoogleServicesDAO {
  override def createGoogleGroup(groupName: String): Option[String] = {
    groupName match {
      case "makeGoogleGroupCreationFail" => Option.empty
      case _ => Option(groupName)
    }
  }
  override def addMemberToAnonymizedGoogleGroup(groupName: String, targetUserEmail: String): Option[String] = {
    groupName match {
      case "makeAddMemberFail" => Option.empty
      case _ => Option(targetUserEmail)
    }
  }
}
