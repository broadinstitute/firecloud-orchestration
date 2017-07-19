package org.broadinstitute.dsde.firecloud.model

import javax.mail.internet.InternetAddress

import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.rawls.model.{MethodConfigurationShort, MethodRepoMethod}

import scala.util.Try

object MethodRepository {

  object AgoraEntityType extends Enumeration {
    type EntityType = Value
    val Task = Value("Task")
    val Workflow = Value("Workflow")
    val Configuration = Value("Configuration")

    def toPath(entityType: EntityType): String = entityType match {
      case Workflow | Task => "methods"
      case Configuration => "configurations"
    }
  }

  case class Configuration(
    namespace: Option[String] = None,
    name: Option[String] = None,
    snapshotId: Option[Int] = None,
    synopsis: Option[String] = None,
    documentation: Option[String] = None,
    owner: Option[String] = None,
    payload: Option[String] = None,
    excludedField: Option[String] = None,
    includedField: Option[String] = None
  )

  case class Method(
    namespace: Option[String] = None,
    name: Option[String] = None,
    snapshotId: Option[Int] = None,
    synopsis: Option[String] = None,
    owner: Option[String] = None,
    createDate: Option[String] = None,
    url: Option[String] = None,
    entityType: Option[String] = None,
    managers: Option[Seq[String]] = None
  ) {
    def toShortString: String = s"Method($namespace,$name,$snapshotId)"
  }

  object Method {
    def apply(mrm:MethodRepoMethod) =
      new Method(Some(mrm.methodNamespace), Some(mrm.methodName), Some(mrm.methodVersion))
    def apply(mrm:MethodRepoMethod, managers:Option[Seq[String]]) =
      new Method(Some(mrm.methodNamespace), Some(mrm.methodName), Some(mrm.methodVersion), managers=managers)
  }

  // represents a method/config permission as exposed to the user from the orchestration layer
  case class FireCloudPermission(
    user: String,
    role: String
  ) {
    require(
      role.equals(ACLNames.NoAccess) || role.equals(ACLNames.Reader) || role.equals(ACLNames.Owner),
      s"role must be one of %s, %s, or %s".format(ACLNames.NoAccess, ACLNames.Reader, ACLNames.Owner)
    )
    require(validatePublicOrEmail(user), "user must be a valid email address or 'public'")
    def toAgoraPermission = AgoraPermissionHandler.toAgoraPermission(this)

  }

  // represents a method/config permission as exposed by Agora
  case class AgoraPermission(
    user: Option[String] = None,
    roles: Option[List[String]] = None
  ) {
    def toFireCloudPermission = AgoraPermissionHandler.toFireCloudPermission(this)
  }

  case class EntityAccessControlAgora(entity: Method, acls: Seq[AgoraPermission], message: Option[String] = None)

  case class MethodAclPair(method:MethodRepoMethod, acls: Seq[FireCloudPermission], message: Option[String] = None)

  case class EntityAccessControl(method:Option[Method], referencedBy: MethodConfigurationName, acls: Seq[FireCloudPermission], message: Option[String] = None)

  object ACLNames {
    val NoAccess = "NO ACCESS"
    val Reader = "READER"
    val Owner = "OWNER"

    // ensure the lists here are pre-sorted, because in AgoraPermissionHandler we pattern-match on sorted lists!
    // yes we could manually sort these, but I prefer using .sorted - it's a one-time init, and it eliminates human mistakes
    val ListNoAccess = List("Nothing")
    val ListReader = List("Read")
    val ListOwner = List("Read","Write","Create","Redact","Manage").sorted
    val ListAll = List("All")
  }

  def validatePublicOrEmail(email:String): Boolean = {
    "public".equals(email) || Try(new InternetAddress(email).validate()).isSuccess
  }


}
