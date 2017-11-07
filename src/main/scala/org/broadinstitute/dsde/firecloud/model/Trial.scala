package org.broadinstitute.dsde.firecloud.model

import java.time.Instant

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.TrialState

import scala.util.Try

object Trial {

  object TrialStates {
    sealed trait TrialState  {
      override def toString: String = TrialStates.stringify(this)
      def withName(name: String): TrialState = TrialStates.withName(name)
    }

    def withName(name: String): TrialState = {
      name match {
        case "Enabled" => Enabled
        case "Enrolled" => Enrolled
        case "Terminated" => Terminated
        case _ => throw new FireCloudException(s"invalid TrialState [$name]")
      }
    }

    def stringify(state: TrialState): String = {
      state match {
        case Enabled => "Enabled"
        case Enrolled => "Enrolled"
        case Terminated => "Terminated"
        case _ => throw new FireCloudException(s"invalid TrialState [${state.getClass.getName}]")
      }
    }

    case object Enabled extends TrialState
    case object Enrolled extends TrialState
    case object Terminated extends TrialState
  }

  case class UserTrialStatus(
    userId: String,
    currentState: Option[TrialState],
    enabledDate: Instant,    // timestamp a campaign manager granted the user trial permissions
    enrolledDate: Instant,   // timestamp user started their trial
    terminatedDate: Instant, // timestamp user was actually terminated
    expirationDate: Instant  // timestamp user is due to face termination
    )

  object UserTrialStatus {
    // convenience apply method that accepts epoch millisecond times instead of java.time.Instants
    def apply(userId: String, currentState: Option[TrialState],
              enabledEpoch: Long, enrolledEpoch: Long, terminatedEpoch: Long, expirationEpoch: Long) = {
      new UserTrialStatus(userId, currentState,
        Instant.ofEpochMilli(enabledEpoch),
        Instant.ofEpochMilli(enrolledEpoch),
        Instant.ofEpochMilli(terminatedEpoch),
        Instant.ofEpochMilli(expirationEpoch)
      )
    }
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

      val currentState = mappedKVPs.get("trialCurrentState") map TrialStates.withName

      new UserTrialStatus(profileWrapper.userId, currentState,
        enabledDate, enrolledDate, terminatedDate, expirationDate)
    }

    def toKVPs(userTrialStatus: UserTrialStatus): Map[String,String] = {
      val stateKV:Map[String,String] = userTrialStatus.currentState match {
        case Some(state) => Map("trialCurrentState" -> state.toString)
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

}
