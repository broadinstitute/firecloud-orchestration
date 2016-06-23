package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.FireCloudConfig

/**
 * Created by mbemis on 6/22/16.
 */
sealed trait Notification {
  val userId: String
  val notificationId: String
  def toMap: Map[String, String]
}

case class ActivationNotification(recipientUserId: String) extends Notification {
  val userId = recipientUserId
  val notificationId = FireCloudConfig.Notification.activationTemplateId
  def toMap: Map[String, String] = Map.empty
}
