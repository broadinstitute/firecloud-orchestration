package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.SamResource.{AccessPolicyName, ResourceId, UserPolicy}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.json.DefaultJsonProtocol._

import scala.util.parsing.json.JSONFormat

class SamResourceSpec extends AnyFreeSpec with Matchers {

  val userPolicyJSON =     """
    |  {
    |    "resourceId": "8011932d-d76e-4c5d-9f66-1538d86a683b",
    |    "public": true,
    |    "accessPolicyName": "reader",
    |    "missingAuthDomainGroups": [],
    |    "authDomainGroups": []
    |    }
    """.stripMargin

  val userPolicyListJSON =
    """
    |  [{
    |    "resourceId": "8011932d-d76e-4c5d-9f66-1538d86a683b",
    |    "public": true,
    |    "accessPolicyName": "reader",
    |    "missingAuthDomainGroups": [],
    |    "authDomainGroups": []
    |  },
    |  {
    |    "resourceId": "195feff3-d4b0-43df-9d0d-d49eda2036eb",
    |    "public": false,
    |    "accessPolicyName": "owner",
    |    "missingAuthDomainGroups": [],
    |    "authDomainGroups": []
    |  },
    |  {
    |    "resourceId": "a2e2a933-76ed-4679-a3c1-fcec146441b5",
    |    "public": false,
    |    "accessPolicyName": "owner",
    |    "missingAuthDomainGroups": [],
    |    "authDomainGroups": []
    |  }]
  """.stripMargin

  "UserPolicy JSON" - {

    "should convert to UserPolicy object" in {
      val jsobj: JsValue = JsonParser(userPolicyJSON)
      val userPolicy: UserPolicy = jsobj.convertTo[UserPolicy]
      assert(userPolicy.public)
      assertResult("reader"){ userPolicy.accessPolicyName.value }
      }
    }

  }
