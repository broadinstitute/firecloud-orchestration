package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{Notification, Profile, UserInfo}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Try}


/**
 * Created by mbemis on 10/25/16.
 *
 */
class MockThurloeDAO extends ThurloeDAO {

  override def sendNotifications(notifications: Seq[Notification]): Future[Try[Unit]] = Future(Success(()))

  override def getProfile(userInfo: UserInfo): Future[Option[Profile]] =
    Future(Some(Profile("Rich", "Hickey", "CTO", None, "Datomic", "Data Storage",
      "NYC", "NY", "USA", "David Mohs", "Not for no profit")))

  override def maybeUpdateNihLinkExpiration(userInfo: UserInfo, profile: Profile): Future[Unit] =
    Future(())
}
