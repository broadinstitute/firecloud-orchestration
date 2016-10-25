package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.FireCloudConfig

/**
 * Created by mbemis on 6/22/16.
 */

sealed trait Notification {
  val userId: String
  val replyTo: Option[String]
  val notificationId: String
  def toMap: Map[String, String]
  def workspacePortalUrl = FireCloudConfig.FireCloud.fireCloudPortalUrl + "/#workspaces/%s:%s"
}

case class ActivationNotification(recipientUserId: String) extends Notification {
  val userId = recipientUserId
  val replyTo = None
  val notificationId = FireCloudConfig.Notification.activationTemplateId
  def toMap: Map[String, String] = Map.empty
}

case class WorkspaceAddedNotification(recipientUserId: String, accessLevel: String, workspaceNamespace: String, workspaceName: String, originEmail: String) extends Notification {
  val userId = recipientUserId
  val replyTo = Some(originEmail)
  val notificationId = FireCloudConfig.Notification.workspaceAddedTemplateId
  def toMap: Map[String, String] = Map("accessLevel" -> accessLevel,
    "namespace" -> workspaceNamespace,
    "name" -> workspaceName,
    "wsUrl" -> workspacePortalUrl.format(workspaceNamespace, workspaceName),
    "originEmail" -> originEmail)
}

case class WorkspaceRemovedNotification(recipientUserId: String, accessLevel: String, workspaceNamespace: String, workspaceName: String, originEmail: String) extends Notification {
  val userId = recipientUserId
  val replyTo = Some(originEmail)
  val notificationId = FireCloudConfig.Notification.workspaceRemovedTemplateId
  def toMap: Map[String, String] = Map("accessLevel" -> accessLevel,
    "namespace" -> workspaceNamespace,
    "name" -> workspaceName,
    "wsUrl" -> workspacePortalUrl.format(workspaceNamespace, workspaceName),
    "originEmail" -> originEmail)
}