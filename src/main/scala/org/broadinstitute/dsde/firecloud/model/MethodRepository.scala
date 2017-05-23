package org.broadinstitute.dsde.firecloud.model

import javax.mail.internet.InternetAddress

import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler

import scala.util.Try

object MethodRepository {

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
    documentation: Option[String] = None,
    createDate: Option[String] = None,
    url: Option[String] = None,
    payload: Option[String] = None,
    entityType: Option[String] = None
  )

  case class MethodId(
    namespace: String,
    name: String,
    snapshotId: Int
  )

  case class MethodCreate(
    namespace: String,
    name: String,
    synopsis: String,
    documentation: String,
    payload: String,
    entityType: String
  )

  case class EditMethodRequest(
    source: MethodId,
    synopsis: String,
    documentation: String,
    payload: String,
    redactOldSnapshot: Boolean
  )

  case class EditMethodResponse(
    method: Method,
    message: Option[String] = None
  )

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
