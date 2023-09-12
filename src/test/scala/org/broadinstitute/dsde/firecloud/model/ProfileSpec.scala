package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.HealthChecks.termsOfServiceUrl
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ProfileSpec extends AnyFreeSpec with Matchers {

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
          researchArea = Some(randomString),
          programLocationCity = randomString,
          programLocationState = randomString,
          programLocationCountry = randomString,
          termsOfService = Some(termsOfServiceUrl),
          department = Some(randomString),
          interestInTerra = Some(randomString)
        )
        basicProfile shouldNot be(null)
      }
      "BasicProfile with well-formed contact email (example #2) is valid" in {
        val basicProfile = BasicProfile(
          firstName = randomString,
          lastName = randomString,
          title = randomString,
          contactEmail = Some("user@theiagen.training"),
          institute = randomString,
          researchArea = Some(randomString),
          programLocationCity = randomString,
          programLocationState = randomString,
          programLocationCountry = randomString,
          termsOfService = Some(termsOfServiceUrl),
          department = Some(randomString),
          interestInTerra = Some(randomString)
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
          researchArea = Some(randomString),
          programLocationCity = randomString,
          programLocationState = randomString,
          programLocationCountry = randomString,
          department = Some(randomString),
          interestInTerra = Some(randomString)
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
          researchArea = Some(randomString),
          programLocationCity = randomString,
          programLocationState = randomString,
          programLocationCountry = randomString,
          department = Some(randomString),
          interestInTerra = Some(randomString)
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
          researchArea = Some(randomString),
          programLocationCity = randomString,
          programLocationState = randomString,
          programLocationCountry = randomString,
          department = Some(randomString),
          interestInTerra = Some(randomString)
        )
        profile shouldNot be(null)
      }

      "Profile instantiated with ProfileWrapper is valid" in {
        val pw = ProfileWrapper("123", List(
          FireCloudKeyValue(Some("firstName"), Some("test-firstName")),
            FireCloudKeyValue(Some("lastName"), Some("test-lastName")),
            FireCloudKeyValue(Some("title"), Some("test-title")),
            FireCloudKeyValue(Some("institute"), Some("test-institute")),
            FireCloudKeyValue(Some("department"), Some("test-department")),
            FireCloudKeyValue(Some("programLocationCity"), Some("test-programLocationCity")),
            FireCloudKeyValue(Some("programLocationState"), Some("test-programLocationState")),
            FireCloudKeyValue(Some("programLocationCountry"), Some("test-programLocationCountry")),
            FireCloudKeyValue(Some("contactEmail"), Some("test-contactEmail@noreply.com"))
        ))
        val profile = Profile(pw)
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
            contactEmail = None,
            institute = "",
            programLocationCity = "",
            programLocationState = "",
            programLocationCountry = "",
            None,
            None,
            None,
            None
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
            researchArea = Some(randomString),
            programLocationCity = randomString,
            programLocationState = randomString,
            programLocationCountry = randomString,
            department = Some(randomString),
            interestInTerra = Some(randomString)
          )
        }
        ex shouldNot be(null)
      }
    }

  }

  "ProfileUtils" - {

    val pw = ProfileWrapper("123", List(
      FireCloudKeyValue(Some("imastring"), Some("hello")),
      FireCloudKeyValue(Some("imalong"), Some("1556724034")),
      FireCloudKeyValue(Some("imnotalong"), Some("not-a-long")),
      FireCloudKeyValue(Some("imnothing"), None)
    ))

    "getString" - {
      "returns None if key doesn't exist" in {
        val targetKey = "nonexistent"
        // assert key does not exist in sample data
        pw.keyValuePairs.find(_.key.contains(targetKey)) shouldBe None
        // and therefore getString returns None
        val actual = ProfileUtils.getString(targetKey, pw)
        actual shouldBe None
      }
      "returns None if key exists but value doesn't" in {
        val targetKey = "imnothing"
        // assert key exists in sample data with no value
        val targetKV = pw.keyValuePairs.find(_.key.contains(targetKey))
        targetKV.isDefined shouldBe true
        targetKV.get.value shouldBe None

        val actual = ProfileUtils.getString(targetKey, pw)
        actual shouldBe None
      }
      "returns Some(String) if key and value exist" in {
        val targetKey = "imastring"
        val actual = ProfileUtils.getString(targetKey, pw)
        actual shouldBe Some("hello")
      }
    }
    "getLong" - {
      "returns None if key doesn't exist" in {
        val targetKey = "nonexistent"
        // assert key does not exist in sample data
        pw.keyValuePairs.find(_.key.contains(targetKey)) shouldBe None
        // and therefore getString returns None
        val actual = ProfileUtils.getLong(targetKey, pw)
        actual shouldBe None

      }
      "returns None if key exists but value doesn't" in {
        val targetKey = "imnothing"
        // assert key exists in sample data with no value
        val targetKV = pw.keyValuePairs.find(_.key.contains(targetKey))
        targetKV.isDefined shouldBe true
        targetKV.get.value shouldBe None

        val actual = ProfileUtils.getLong(targetKey, pw)
        actual shouldBe None
      }
      "returns None if key and value exist but value is not a Long" in {
        val targetKey = "imnotalong"
        // assert the key exists
        ProfileUtils.getString(targetKey, pw) shouldBe Some("not-a-long")
        // but can't be parsed as a Long
        val actual = ProfileUtils.getLong(targetKey, pw)
        actual shouldBe None
      }
      "returns Some(Long) if key and value exist and value is Long-able" in {
        val targetKey = "imalong"
        val actual = ProfileUtils.getLong(targetKey, pw)
        actual shouldBe Some(1556724034L)
      }
    }
  }

}
