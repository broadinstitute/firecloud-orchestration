package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler

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
    owner: Option[String] = None,
    createDate: Option[String] = None,
    url: Option[String] = None,
    entityType: Option[String] = None
  )

  // represents a method/config permission as exposed to the user from the orchestration layer
  case class FireCloudPermission(
    user: Option[String] = None,
    role: Option[String] = None
  ) {
    def verifyValidUserAndRole = AgoraPermissionHandler.hasValidUserAndRole(this)
    def toAgoraPermission = AgoraPermissionHandler.toAgoraPermission(this)
  }

  // represents a method/config permission as exposed by Agora
  case class AgoraPermission(
    user: Option[String] = None,
    roles: Option[List[String]] = None
  ) {
    def toFireCloudPermission = AgoraPermissionHandler.toFireCloudPermission(this)
  }

}
