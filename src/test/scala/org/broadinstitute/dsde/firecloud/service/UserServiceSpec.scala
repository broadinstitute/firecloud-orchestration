package org.broadinstitute.dsde.firecloud.service

import akka.testkit.TestActorRef
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.{MockLogitDAO, MockResearchPurposeSupport, MockShareLogDAO}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.scalatest.BeforeAndAfterEach
import spray.http.OAuth2BearerToken

class UserServiceSpec  extends BaseServiceSpec with BeforeAndAfterEach {

  val customApp = Application(agoraDao, googleServicesDao, ontologyDao, consentDao, new MockRawlsDeleteWSDAO(), samDao, new MockSearchDeleteWSDAO(), new MockResearchPurposeSupport, thurloeDao, trialDao, new MockLogitDAO, new MockShareLogDAO)

  val userServiceConstructor: (UserInfo) => UserService = UserService.constructor(customApp)

  lazy val userService: UserService = TestActorRef(UserService.props(userServiceConstructor, UserInfo("me@me.com", OAuth2BearerToken(""), 3600, "111"))).underlyingActor


  "UserService " - {
    "getAllUserKeys" - {
      "form a proper email address" in {
        //todo: may want to match against a regex or something but this will probably suffice
        val anonymousGroupName = userService.getNewAnonymousGroupName
        anonymousGroupName should endWith(FireCloudConfig.FireCloud.supportDomain)
        anonymousGroupName should startWith(FireCloudConfig.FireCloud.supportPrefix)
      }
    }
  }

  //test case(s) for writeAnonymousGroup

  //test case(s) for getWord

  //test case(s) for setupAnonymizedGoogleGroup

  //test case(s) for getAllUserKeys

  //above where customApp is defined, we've got googleServicesDao which is an instance of the MockGoogleServicesDAO. that mock version will be called instead of the real Http implementation
}
