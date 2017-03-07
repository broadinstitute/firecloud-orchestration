package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{BasicProfile, Profile, UserInfo}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Try}


/**
 * Created by mbemis on 10/25/16.
 *
 */
class MockThurloeDAO extends ThurloeDAO {
  var nextGetProfileResponse:Option[Profile] = None
  val testProfile = Profile("Rich", "Hickey", "CTO", None, "Datomic", "Data Storage",
    "NYC", "NY", "USA", "David Mohs", "Not for no profit")

  // Don't copy this pattern. See GAWB-1477.
  def reset() = {
    nextGetProfileResponse = None
  }


  override def getProfile(userInfo: UserInfo): Future[Option[Profile]] =
    Future(nextGetProfileResponse)

  override def saveKeyValue(userInfo: UserInfo, key: String, value: String): Future[Unit] =
    Future(())

  override def saveProfile(userInfo: UserInfo, profile: BasicProfile): Future[Unit] =
    Future(())

  override def maybeUpdateNihLinkExpiration(userInfo: UserInfo, profile: Profile): Future[Unit] =
    Future(())
}
