package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.rawls.model.{RawlsBillingProjectName, RawlsEnumeration, RawlsUserEmail}

object Project {

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
