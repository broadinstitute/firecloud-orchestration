package org.broadinstitute.dsde.firecloud.model

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
  val notificationId = "8c646115-d58b-45c2-a8ea-a4f5d47d5d2e"
  def toMap: Map[String, String] = Map.empty
}
