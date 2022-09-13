package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail

/**
  * Created by mbemis on 3/29/18.
  */

object ManagedGroupRoles {
  sealed trait ManagedGroupRole {
    override def toString: String = {
      this match {
        case Admin => "admin"
        case Member => "member"
        case AdminNotifier => "admin-notifier"
        case _ => throw new Exception(s"invalid ManagedGroupRole [$this]")
      }
    }

    def withName(name: String): ManagedGroupRole = ManagedGroupRoles.withName(name)
  }

  //we'll match on singular and plural for these roles because there's some inconsistency introduced
  //between orch and sam. this maintains backwards compatibility and as a bonus is a bit more user-friendly
  def withName(name: String): ManagedGroupRole = name.toLowerCase match {
    case role if role matches "(?i)admin(s?$)" => Admin
    case role if role matches "(?i)member(s?$)" => Member
    case role if role matches "(?i)admin-notifier(s?$)" => AdminNotifier
    case _ => throw new Exception(s"invalid ManagedGroupRole [$name]")
  }

  case object Admin extends ManagedGroupRole
  case object Member extends ManagedGroupRole
  case object AdminNotifier extends ManagedGroupRole

  val membershipRoles: Set[ManagedGroupRole] = Set(Admin, Member)
}

case class FireCloudManagedGroup(adminsEmails: List[WorkbenchEmail], membersEmails: List[WorkbenchEmail], groupEmail: WorkbenchEmail)
case class FireCloudManagedGroupMembership(groupName: String, groupEmail: String, role: String)