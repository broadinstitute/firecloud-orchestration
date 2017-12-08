package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.Trial.{TrialStates, UserTrialStatus}
import org.scalatest.FreeSpec

class TrialSpec extends FreeSpec  {

  "TrialStates" - {
    "should create from known strings" in {
      assertResult(TrialStates.Enabled) { TrialStates.withName("Enabled") }
      assertResult(TrialStates.Enrolled) { TrialStates.withName("Enrolled") }
      assertResult(TrialStates.Terminated) { TrialStates.withName("Terminated") }
    }
    "should error from unknown strings" in {
      intercept[FireCloudException] {
        TrialStates.withName("enabled") // incorrect case
      }
      intercept[FireCloudException] {
        TrialStates.withName("")
      }
      intercept[FireCloudException] {
        TrialStates.withName("Impending") // haven't implemented Impending state
      }
    }
    "should toString predictably" in {
      assertResult("Enabled") { TrialStates.Enabled.toString }
      assertResult("Enrolled") { TrialStates.Enrolled.toString }
      assertResult("Terminated") { TrialStates.Terminated.toString }
    }
    "the Disabled state" - {
      val testState = TrialStates.Disabled
      "should not allow from None" in {
        assert(!testState.isAllowedFrom(None))
      }
      "should allow from Enabled" in {
        assert(testState.isAllowedFrom(Some(TrialStates.Enabled)))
      }
      "should disallow from anything other than Enabled" - {
        (TrialStates.allStates.toSet - TrialStates.Enabled) foreach { state =>
          s"${state.toString}" in {
            assert(!testState.isAllowedFrom(Some(state)))
          }
        }
      }
    }
    "the Enabled state" - {
      val testState = TrialStates.Enabled
      "should allow from None" in {
        assert(testState.isAllowedFrom(None))
      }
      "should allow from Disabled" in {
        assert(testState.isAllowedFrom(Some(TrialStates.Disabled)))
      }
      "should disallow from anything other than Disabled" - {
        (TrialStates.allStates.toSet - TrialStates.Disabled) foreach { state =>
          s"${state.toString}" in {
            assert(!testState.isAllowedFrom(Some(state)))
          }
        }
      }
    }
    "the Enrolled state" - {
      val testState = TrialStates.Enrolled
      "should not allow from None" in {
        assert(!testState.isAllowedFrom(None))
      }
      "should allow from Enabled" in {
        assert(testState.isAllowedFrom(Some(TrialStates.Enabled)))
      }
      "should disallow from anything other than Enabled" - {
        (TrialStates.allStates.toSet - TrialStates.Enabled) foreach { state =>
          s"${state.toString}" in {
            assert(!testState.isAllowedFrom(Some(state)))
          }
        }
      }
    }
    "the Terminated state" - {
      val testState = TrialStates.Terminated
      "should not allow from None" in {
        assert(!testState.isAllowedFrom(None))
      }
      "should allow from Enrolled" in {
        assert(testState.isAllowedFrom(Some(TrialStates.Enrolled)))
      }
      "should disallow from anything other than Enrolled" - {
        (TrialStates.allStates.toSet - TrialStates.Enrolled) foreach { state =>
          s"${state.toString}" in {
            assert(!testState.isAllowedFrom(Some(state)))
          }
        }
      }
    }
  }

  "UserTrialStatus" - {
    "should create from fully-populated Thurloe profile" in {
      val profileWrapper = ProfileWrapper("userid", List(
        FireCloudKeyValue(Some("trialEnabledDate"), Some("12")),
        FireCloudKeyValue(Some("trialEnrolledDate"), Some("34")),
        FireCloudKeyValue(Some("trialTerminatedDate"), Some("56")),
        FireCloudKeyValue(Some("trialExpirationDate"), Some("78")),
        FireCloudKeyValue(Some("trialState"), Some("Enrolled")),
        FireCloudKeyValue(Some("userAgreed"), Some("true"))
        FireCloudKeyValue(Some("trialBillingProjectName"), Some("testProject"))
      ))
      val actual = UserTrialStatus(profileWrapper)
      val expected = UserTrialStatus("userid", Some(TrialStates.Enrolled), true, 12, 34, 56, 78, Some("testProject"))
      assertResult(expected) { actual }
    }
    "should create with empty state and zero timestamps when nothing in profile" in {
      val profileWrapper = ProfileWrapper("userid", List.empty[FireCloudKeyValue])
      val actual = UserTrialStatus(profileWrapper)
      val expected = UserTrialStatus("userid", None, false, 0, 0, 0, 0, None)
      assertResult(expected) { actual }
    }
    "should partially populate timestamps when only some in profile" in {
      val profileWrapper = ProfileWrapper("userid", List(
        FireCloudKeyValue(Some("trialEnabledDate"), Some("12")),
        FireCloudKeyValue(Some("trialState"), Some("Enabled")),
        FireCloudKeyValue(Some("userAgreed"), Some("true"))
        FireCloudKeyValue(Some("trialBillingProjectName"), Some("testProject"))
      ))
      val actual = UserTrialStatus(profileWrapper)
      val expected = UserTrialStatus("userid", Some(TrialStates.Enabled), true, 12, 0, 0, 0, Some("testProject"))
      assertResult(expected) { actual }
    }
    "should convert full UserTrialStatus to Thurloe KVPs" in {
      val userTrialStatus = UserTrialStatus("userid", Some(TrialStates.Terminated), false, 12, 34, 56, 78, Some("testProject"))
      val actual = UserTrialStatus.toKVPs(userTrialStatus)
      val expected = Map(
        "trialEnabledDate" -> "12",
        "trialEnrolledDate" -> "34",
        "trialTerminatedDate" -> "56",
        "trialExpirationDate" -> "78",
        "trialState" -> "Terminated",
        "userAgreed" -> "false",
        "trialBillingProjectName" -> "testProject"
      )
      assertResult(expected) { actual }
    }
    "should convert partial UserTrialStatus to Thurloe KVPs" in {
      val userTrialStatus = UserTrialStatus("userid", Some(TrialStates.Enabled), true, 12, 0, 0, 0, None)
      val actual = UserTrialStatus.toKVPs(userTrialStatus)
      val expected = Map(
        "trialEnabledDate" -> "12",
        "trialEnrolledDate" -> "0",
        "trialTerminatedDate" -> "0",
        "trialExpirationDate" -> "0",
        "trialState" -> "Enabled",
        "userAgreed" -> "true"
      )
      assertResult(expected) { actual }
    }
    "should convert empty UserTrialStatus to Thurloe KVPs" in {
      val userTrialStatus = UserTrialStatus("userid", None, false, 0, 0, 0, 0, None)
      val actual = UserTrialStatus.toKVPs(userTrialStatus)
      val expected = Map(
        "trialEnabledDate" -> "0",
        "trialEnrolledDate" -> "0",
        "trialTerminatedDate" -> "0",
        "trialExpirationDate" -> "0",
        "userAgreed" -> "false"
      )
      assertResult(expected) { actual }
    }
  }

}
