package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, Notification, Profile, UserInfo}

import scala.concurrent.Future
import scala.util.Try

/**
 * Created by mbemis on 10/21/16.
 */
trait ThurloeDAO extends LazyLogging {

  def sendNotifications(notifications: Seq[Notification]): Future[Try[Unit]]
  def getProfile(userInfo: UserInfo): Future[Option[Profile]]
  def saveKeyValue(userInfo: UserInfo, key: String, value: String): Future[Unit]
  def saveProfile(userInfo: UserInfo, profile: BasicProfile): Future[Unit]
  def maybeUpdateNihLinkExpiration(userInfo: UserInfo, profile: Profile): Future[Unit]
}
