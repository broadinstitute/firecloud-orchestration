package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.scalatest.{FreeSpec, Matchers}

class ProfileSpec extends FreeSpec with Matchers {

  val randomString = MockUtils.randomAlpha()

  "Profile" - {

    "Correctly formed profiles" - {
      "BasicProfile with well-formed contact email is valid" in {
        val basicProfile = BasicProfile(
          firstName = randomString,
          lastName = randomString,
          title = randomString,
          contactEmail = Some("me@abc.com"),
          institute = randomString,
          institutionalProgram = randomString,
          programLocationCity = randomString,
          programLocationState = randomString,
          programLocationCountry = randomString,
          pi = randomString,
          nonProfitStatus = randomString
        )
        basicProfile shouldNot be(null)
      }
      "Profile with blank contact email is valid" in {
        val profile = Profile(
          firstName = randomString,
          lastName = randomString,
          title = randomString,
          contactEmail = Some(""),
          institute = randomString,
          institutionalProgram = randomString,
          programLocationCity = randomString,
          programLocationState = randomString,
          programLocationCountry = randomString,
          pi = randomString,
          nonProfitStatus = randomString
        )
        profile shouldNot be(null)
      }
      "Profile with empty contact email is valid" in {
        val profile = Profile(
          firstName = randomString,
          lastName = randomString,
          title = randomString,
          contactEmail = Option.empty,
          institute = randomString,
          institutionalProgram = randomString,
          programLocationCity = randomString,
          programLocationState = randomString,
          programLocationCountry = randomString,
          pi = randomString,
          nonProfitStatus = randomString
        )
        profile shouldNot be(null)
      }
      "Profile with contact email containing '+' is valid" in {
        val profile = Profile(
          firstName = randomString,
          lastName = randomString,
          title = randomString,
          contactEmail = Some("a-z+a.b-x+y.z@gmail.com"),
          institute = randomString,
          institutionalProgram = randomString,
          programLocationCity = randomString,
          programLocationState = randomString,
          programLocationCountry = randomString,
          pi = randomString,
          nonProfitStatus = randomString
        )
        profile shouldNot be(null)
      }
    }

    "Incorrectly formed profiles" - {
      "BasicProfile with blank required info is invalid" in {
        val ex = intercept[IllegalArgumentException]{
          BasicProfile(
            firstName = "",
            lastName = "",
            title = "",
            contactEmail = Option.empty,
            institute = "",
            institutionalProgram = "",
            programLocationCity = "",
            programLocationState = "",
            programLocationCountry = "",
            pi = "",
            nonProfitStatus = ""
          )
        }
        ex shouldNot be(null)
      }
      "Profile with invalid contact email is invalid" in {
        val ex = intercept[IllegalArgumentException]{
          Profile(
            firstName = randomString,
            lastName = randomString,
            title = randomString,
            contactEmail = Some("invalid contact email address"),
            institute = randomString,
            institutionalProgram = randomString,
            programLocationCity = randomString,
            programLocationState = randomString,
            programLocationCountry = randomString,
            pi = randomString,
            nonProfitStatus = randomString
          )
        }
        ex shouldNot be(null)
      }
    }

  }

}
