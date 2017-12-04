package org.broadinstitute.dsde.firecloud.model

import java.time.Instant

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.TrialState
import org.broadinstitute.dsde.rawls.model.RawlsBillingProjectName

import scala.util.Try

object Trial {
  object TrialOperations {
    sealed trait TrialOperation

    case object Enable extends TrialOperation
    case object Disable extends TrialOperation
    case object Terminate extends TrialOperation

    def withName(name: String): TrialOperation = name match {
      case "enable" => Enable
      case "disable" => Disable
      case "terminate" => Terminate
      case _ => throw new FireCloudException(s"Invalid trial operation: [$name]")
    }
  }

  // enum-style case objects to track a user's progress through the free trial
  object TrialStates {
    sealed trait TrialState  {
      override def toString: String = TrialStates.stringify(this)
      def withName(name: String): TrialState = TrialStates.withName(name)
      def isAllowedFrom(previous: Option[TrialState]) = {
        previous match {
          case None => isAllowedFromNone
          case Some(state) => isAllowedFromState(state)
        }
      }
      def isAllowedFromState(previous: TrialState): Boolean
      def isAllowedFromNone: Boolean = false
    }

    val allStates = Seq(Disabled, Enabled, Enrolled, Terminated)

    def withName(name: String): TrialState = {
      name match {
        case "Disabled" => Disabled
        case "Enabled" => Enabled
        case "Enrolled" => Enrolled
        case "Terminated" => Terminated
        case _ => throw new FireCloudException(s"invalid TrialState [$name]")
      }
    }

    def stringify(state: TrialState): String = {
      state match {
        case Disabled => "Disabled"
        case Enabled => "Enabled"
        case Enrolled => "Enrolled"
        case Terminated => "Terminated"
        case _ => throw new FireCloudException(s"invalid TrialState [${state.getClass.getName}]")
      }
    }

    case object Disabled extends TrialState  {
      override def isAllowedFromState(previous: TrialState): Boolean = previous == Enabled
    }
    case object Enabled extends TrialState {
      override def isAllowedFromNone: Boolean = true
      override def isAllowedFromState(previous: TrialState): Boolean = previous == Disabled
    }
    case object Enrolled extends TrialState {
      override def isAllowedFromState(previous: TrialState): Boolean = previous == Enabled
    }
    case object Terminated extends TrialState {
      override def isAllowedFromState(previous: TrialState): Boolean = previous == Enrolled
    }
  }

  // "profile" object to hold all free trial-related information for a single user
  case class UserTrialStatus(
      userId: String,
      state: Option[TrialState],
      enabledDate: Instant = Instant.ofEpochMilli(0), // timestamp a campaign manager granted the user trial permissions
      enrolledDate: Instant = Instant.ofEpochMilli(0), // timestamp user started their trial
      terminatedDate: Instant = Instant.ofEpochMilli(0), // timestamp user was actually terminated
      expirationDate: Instant = Instant.ofEpochMilli(0) // timestamp user is due to face termination
    )

  object UserTrialStatus {
    // convenience apply method that accepts epoch millisecond times instead of java.time.Instants
    def apply(userId: String, state: Option[TrialState],
              enabledEpoch: Long, enrolledEpoch: Long, terminatedEpoch: Long, expirationEpoch: Long) = {
      new UserTrialStatus(userId, state,
        Instant.ofEpochMilli(enabledEpoch),
        Instant.ofEpochMilli(enrolledEpoch),
        Instant.ofEpochMilli(terminatedEpoch),
        Instant.ofEpochMilli(expirationEpoch)
      )
    }
    // apply method to create a UserTrialStatus from raw Thurloe KVPs
    def apply(profileWrapper:ProfileWrapper) = {

      def profileDate(key: String, kvps: Map[String,String], default: Instant = Instant.ofEpochMilli(0)): Instant = {
        Try(Instant.ofEpochMilli(kvps.getOrElse(key, default.toString).toLong)).toOption.getOrElse(default)
      }

      val mappedKVPs:Map[String,String] = (profileWrapper.keyValuePairs collect {
        case kvp if kvp.key.nonEmpty && kvp.value.nonEmpty => kvp.key.get -> kvp.value.get
      }).toMap

      val enabledDate = profileDate("trialEnabledDate", mappedKVPs)
      val enrolledDate = profileDate("trialEnrolledDate", mappedKVPs)
      val terminatedDate = profileDate("trialTerminatedDate", mappedKVPs)
      val expirationDate = profileDate("trialExpirationDate", mappedKVPs)

      val state = mappedKVPs.get("trialState") map TrialStates.withName

      new UserTrialStatus(profileWrapper.userId, state,
        enabledDate, enrolledDate, terminatedDate, expirationDate)
    }

    // translates a UserTrialStatus to Thurloe KVPs
    def toKVPs(userTrialStatus: UserTrialStatus): Map[String,String] = {
      val stateKV:Map[String,String] = userTrialStatus.state match {
        case Some(state) => Map("trialState" -> state.toString)
        case None => Map.empty[String,String]
      }
      Map(
        "trialEnabledDate" -> userTrialStatus.enabledDate.toEpochMilli.toString,
        "trialEnrolledDate" -> userTrialStatus.enrolledDate.toEpochMilli.toString,
        "trialTerminatedDate" -> userTrialStatus.terminatedDate.toEpochMilli.toString,
        "trialExpirationDate" -> userTrialStatus.expirationDate.toEpochMilli.toString
      ) ++ stateKV
    }

  }

  case class TrialProject(name: RawlsBillingProjectName, verified: Boolean, user: Option[WorkbenchUserInfo])
  object TrialProject {
    def apply(name: RawlsBillingProjectName) = new TrialProject(name, verified=false, user=None)
  }

  object StatusUpdate {
    sealed trait Attempt

    case object Success extends Attempt
    case object Failure extends Attempt
    case class ServerError(msg: String) extends Attempt
  }
}
