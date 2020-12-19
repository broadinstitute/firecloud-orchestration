package org.broadinstitute.dsde.firecloud.model

import javax.mail.internet.InternetAddress
import org.broadinstitute.dsde.firecloud.service.AgoraPermissionService
import org.broadinstitute.dsde.rawls.model.{AgoraMethod, DockstoreMethod, MethodConfigurationShort, MethodRepoMethod}

import scala.util.Try

object OrchMethodRepository {

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
    snapshotComment: Option[String] = None,
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
    snapshotComment: Option[String] = None,
    synopsis: Option[String] = None,
    owner: Option[String] = None,
    createDate: Option[String] = None,
    url: Option[String] = None,
    entityType: Option[String] = None,
    managers: Option[Seq[String]] = None,
    public: Option[Boolean] = None
  ) {
    def toShortString: String = s"Method($namespace,$name,$snapshotId)"
  }

  case class AgoraConfigurationShort(
    name: String,
    rootEntityType: String,
    methodRepoMethod: AgoraMethod,
    namespace: String)

  object Method {
    def apply(mrm: AgoraMethod): Method = apply(mrm = mrm, managers = None, public = None)
    def apply(mrm: AgoraMethod, managers:Option[Seq[String]], public:Option[Boolean]): Method =
      new Method(Some(mrm.methodNamespace), Some(mrm.methodName), Some(mrm.methodVersion), managers=managers, public=public)
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
    def toAgoraPermission = AgoraPermissionService.toAgoraPermission(this)

  }

  // represents a method/config permission as exposed by Agora
  case class AgoraPermission(
    user: Option[String] = None,
    roles: Option[List[String]] = None
  ) {
    def toFireCloudPermission = AgoraPermissionService.toFireCloudPermission(this)
  }

  case class EntityAccessControlAgora(entity: Method, acls: Seq[AgoraPermission], message: Option[String] = None)

  case class MethodAclPair(method: AgoraMethod, acls: Seq[FireCloudPermission], message: Option[String] = None)

  case class EntityAccessControl(method:Option[Method], referencedBy: OrchMethodConfigurationName, acls: Seq[FireCloudPermission], message: Option[String] = None)

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
