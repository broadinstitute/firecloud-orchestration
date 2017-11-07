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
  }

  "UserTrialStatus" - {
    "should create from fully-populated Thurloe profile" in {
      val profileWrapper = ProfileWrapper("userid", List(
        FireCloudKeyValue(Some("trialEnabledDate"), Some("12")),
        FireCloudKeyValue(Some("trialEnrolledDate"), Some("34")),
        FireCloudKeyValue(Some("trialTerminatedDate"), Some("56")),
        FireCloudKeyValue(Some("trialExpirationDate"), Some("78")),
        FireCloudKeyValue(Some("trialCurrentState"), Some("Enrolled"))
      ))
      val actual = UserTrialStatus(profileWrapper)
      val expected = UserTrialStatus("userid", Some(TrialStates.Enrolled), 12, 34, 56, 78)
      assertResult(expected) { actual }
    }
    "should create with empty state and zero timestamps when nothing in profile" in {
      val profileWrapper = ProfileWrapper("userid", List.empty[FireCloudKeyValue])
      val actual = UserTrialStatus(profileWrapper)
      val expected = UserTrialStatus("userid", None, 0, 0, 0, 0)
      assertResult(expected) { actual }
    }
    "should partially populate timestamps when only some in profile" in {
      val profileWrapper = ProfileWrapper("userid", List(
        FireCloudKeyValue(Some("trialEnabledDate"), Some("12")),
        FireCloudKeyValue(Some("trialCurrentState"), Some("Enabled"))
      ))
      val actual = UserTrialStatus(profileWrapper)
      val expected = UserTrialStatus("userid", Some(TrialStates.Enabled), 12, 0, 0, 0)
      assertResult(expected) { actual }
    }
    "should convert full UserTrialStatus to Thurloe KVPs" in {
      val userTrialStatus = UserTrialStatus("userid", Some(TrialStates.Terminated), 12, 34, 56, 78)
      val actual = UserTrialStatus.toKVPs(userTrialStatus)
      val expected = Map(
        "trialEnabledDate" -> "12",
        "trialEnrolledDate" -> "34",
        "trialTerminatedDate" -> "56",
        "trialExpirationDate" -> "78",
        "trialCurrentState" -> "Terminated"
      )
      assertResult(expected) { actual }
    }
    "should convert partial UserTrialStatus to Thurloe KVPs" in {
      val userTrialStatus = UserTrialStatus("userid", Some(TrialStates.Enabled), 12, 0, 0, 0)
      val actual = UserTrialStatus.toKVPs(userTrialStatus)
      val expected = Map(
        "trialEnabledDate" -> "12",
        "trialEnrolledDate" -> "0",
        "trialTerminatedDate" -> "0",
        "trialExpirationDate" -> "0",
        "trialCurrentState" -> "Enabled"
      )
      assertResult(expected) { actual }
    }
    "should convert empty UserTrialStatus to Thurloe KVPs" in {
      val userTrialStatus = UserTrialStatus("userid", None, 0, 0, 0, 0)
      val actual = UserTrialStatus.toKVPs(userTrialStatus)
      val expected = Map(
        "trialEnabledDate" -> "0",
        "trialEnrolledDate" -> "0",
        "trialTerminatedDate" -> "0",
        "trialExpirationDate" -> "0"
      )
      assertResult(expected) { actual }
    }
  }

}
