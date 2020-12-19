package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.{ACLNames, FireCloudPermission}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class OrchMethodRepositorySpec extends AnyFreeSpec with Matchers {

  "FireCloudPermission" - {
    "Correctly formed permissions should validate" - {
      "Valid email user" in {
        val permission = FireCloudPermission(
          user = "test@broadinstitute.org",
          role = ACLNames.Owner)
        permission shouldNot be (null)
      }
      "Public user" in {
        val permission = FireCloudPermission(
          user = "public",
          role = ACLNames.Owner)
        permission shouldNot be (null)
      }
    }

    "Incorrectly formed permissions should not validate" - {
      "Empty email" in {
        val ex = intercept[IllegalArgumentException]{
          val permission = FireCloudPermission(
            user = "",
            role = ACLNames.Owner)
        }
        ex shouldNot be(null)
      }
      "Invalid email" in {
        val ex = intercept[IllegalArgumentException]{
          val permission = FireCloudPermission(
            user = "in valid at email.com",
            role = ACLNames.Owner)
        }
        ex shouldNot be(null)
      }
      "Invalid role" in {
        val ex = intercept[IllegalArgumentException]{
          val permission = FireCloudPermission(
            user = "test@broadinstitute.org",
            role = ACLNames.ListNoAccess.head)
        }
        ex shouldNot be(null)
      }
    }

  }

}
