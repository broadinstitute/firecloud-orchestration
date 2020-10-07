package org.broadinstitute.dsde.firecloud.model

import java.time.Instant

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.Project.CreationStatuses.CreationStatus
import org.broadinstitute.dsde.firecloud.model.Project.TrialStates.TrialState
import org.broadinstitute.dsde.rawls.model.{RawlsBillingProjectName, RawlsEnumeration, RawlsUserEmail}

import scala.util.Try

object Project {

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

    def withName(name: String): TrialState = {
      name match {
        case "Disabled" => Disabled
        case "Enabled" => Enabled
        case "Enrolled" => Enrolled
        case "Terminated" => Terminated
        case "Finalized" => Finalized
        case _ => throw new FireCloudException(s"invalid TrialState [$name]")
      }
    }

    def stringify(state: TrialState): String = {
      state match {
        case Disabled => "Disabled"
        case Enabled => "Enabled"
        case Enrolled => "Enrolled"
        case Terminated => "Terminated"
        case Finalized => "Finalized"
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
    case object Finalized extends TrialState {
      override def isAllowedFromState(previous: TrialState): Boolean = previous == Terminated || previous == Finalized
    }
  }

  // "profile" object to hold all free trial-related information for a single user
  case class UserTrialStatus(
                              userId: String,
                              state: Option[TrialState],
                              userAgreed: Boolean,
                              enabledDate: Instant = Instant.ofEpochMilli(0), // timestamp a campaign manager granted the user trial permissions
                              enrolledDate: Instant = Instant.ofEpochMilli(0), // timestamp user started their trial
                              terminatedDate: Instant = Instant.ofEpochMilli(0), // timestamp user was actually terminated
                              expirationDate: Instant = Instant.ofEpochMilli(0), // timestamp user is due to face termination
                              billingProjectName: Option[String] = None
                            )

  object UserTrialStatus {
    // convenience apply method that accepts epoch millisecond times instead of java.time.Instants
    def apply(userId: String, state: Option[TrialState], userAgreed: Boolean,
              enabledEpoch: Long, enrolledEpoch: Long, terminatedEpoch: Long, expirationEpoch: Long, billingProjectName: Option[String]) = {
      new UserTrialStatus(userId, state, userAgreed,
        Instant.ofEpochMilli(enabledEpoch),
        Instant.ofEpochMilli(enrolledEpoch),
        Instant.ofEpochMilli(terminatedEpoch),
        Instant.ofEpochMilli(expirationEpoch),
        billingProjectName
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
      val billingProjectName = mappedKVPs.get("trialBillingProjectName")

      val userAgreed = Try(mappedKVPs.getOrElse("userAgreed", "false").toBoolean).getOrElse(false)

      new UserTrialStatus(profileWrapper.userId, state, userAgreed,
        enabledDate, enrolledDate, terminatedDate, expirationDate, billingProjectName)
    }

    // translates a UserTrialStatus to Thurloe KVPs
    def toKVPs(userTrialStatus: UserTrialStatus): Map[String,String] = {
      val stateKV:Map[String,String] = userTrialStatus.state match {
        case Some(state) => Map("trialState" -> state.toString)
        case None => Map.empty[String,String]
      }
      val billingProjectKV:Map[String,String] = userTrialStatus.billingProjectName match {
        case Some(name) => Map("trialBillingProjectName" -> name)
        case None => Map.empty[String,String]
      }
      Map(
        "trialEnabledDate" -> userTrialStatus.enabledDate.toEpochMilli.toString,
        "trialEnrolledDate" -> userTrialStatus.enrolledDate.toEpochMilli.toString,
        "trialTerminatedDate" -> userTrialStatus.terminatedDate.toEpochMilli.toString,
        "trialExpirationDate" -> userTrialStatus.expirationDate.toEpochMilli.toString,
        "userAgreed" -> userTrialStatus.userAgreed.toString
      ) ++ stateKV ++ billingProjectKV
    }

  }

  // following are horribly copied-and-pasted from rawls core, since they're not available as shared models
  case class CreateRawlsBillingProjectFullRequest(projectName: String, billingAccount: String)

  case class RawlsBillingProjectMembership(projectName: RawlsBillingProjectName, role: ProjectRoles.ProjectRole, creationStatus: CreationStatuses.CreationStatus, message: Option[String] = None)

  case class RawlsBillingProjectMember(email: RawlsUserEmail, role: ProjectRoles.ProjectRole)

  object CreationStatuses {
    sealed trait CreationStatus extends RawlsEnumeration[CreationStatus] {
      override def toString = toName(this)

      override def withName(name: String): CreationStatus = CreationStatuses.withName(name)
    }

    def toName(status: CreationStatus): String = status match {
      case Creating => "Creating"
      case Ready => "Ready"
      case Error => "Error"
    }

    def withName(name: String): CreationStatus = name.toLowerCase match {
      case "creating" => Creating
      case "ready" => Ready
      case "error" => Error
      case _ => throw new FireCloudException(s"invalid CreationStatus [${name}]")
    }

    case object Creating extends CreationStatus
    case object Ready extends CreationStatus
    case object Error extends CreationStatus

    val all: Set[CreationStatus] = Set(Creating, Ready, Error)
    val terminal: Set[CreationStatus] = Set(Ready, Error)
  }

  object ProjectRoles {
    sealed trait ProjectRole extends RawlsEnumeration[ProjectRole] {
      override def toString = toName(this)

      override def withName(name: String): ProjectRole = ProjectRoles.withName(name)
    }

    def toName(role: ProjectRole): String = role match {
      case Owner => "Owner"
      case User => "User"
    }

    def withName(name: String): ProjectRole = name.toLowerCase match {
      case "owner" => Owner
      case "user" => User
      case _ => throw new FireCloudException(s"invalid ProjectRole [${name}]")
    }

    case object Owner extends ProjectRole
    case object User extends ProjectRole

    val all: Set[ProjectRole] = Set(Owner, User)
  }
  // END copy/paste from rawls
}