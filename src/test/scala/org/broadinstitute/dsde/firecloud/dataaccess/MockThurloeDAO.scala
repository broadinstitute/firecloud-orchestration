package org.broadinstitute.dsde.firecloud.dataaccess

import java.util.NoSuchElementException

import org.broadinstitute.dsde.firecloud.model.{BasicProfile, FireCloudKeyValue, Profile, ProfileWrapper, SubsystemStatus, UserInfo}
import org.broadinstitute.dsde.firecloud.utils.DateUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Try}

/**
 * Created by mbemis on 10/25/16.
 *
 */
class MockThurloeDAO extends ThurloeDAO {

  val linkedUserId = "linked-user"
  val linkedAndNoExpiredUserId = "linked-user-no-expire-date"
  val linkedButExpiredUserId = "linked-user-expired-link"
  val normalUserId = "normal-user"
  val linkedAndInvalidExpiredDate = "linked-user-invalid-expire-date"

  val baseProfile = Set(
    FireCloudKeyValue(Some("firstName"), Some("Bobby")),
    FireCloudKeyValue(Some("lastName"), Some("Testerson")),
    FireCloudKeyValue(Some("title"), Some("Mr.")),
    FireCloudKeyValue(Some("contactEmail"), Some("bobby.testerson@example.com")),
    FireCloudKeyValue(Some("institute"), Some("Broad")),
    FireCloudKeyValue(Some("institutionalProgram"), Some("Something")),
    FireCloudKeyValue(Some("programLocationCity"), Some("Cambridge")),
    FireCloudKeyValue(Some("programLocationState"), Some("MA")),
    FireCloudKeyValue(Some("programLocationCountry"), Some("USA")),
    FireCloudKeyValue(Some("pi"), Some("Abby Testerson")),
    FireCloudKeyValue(Some("nonProfitStatus"), Some("true"))
  )

  var mockKeyValues: Map[String, Set[FireCloudKeyValue]] =
    Map(
      linkedUserId -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-dev")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowPlus30Days.toString)))
      ),
      linkedAndNoExpiredUserId -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user")))
      ),
      linkedButExpiredUserId -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user2")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowMinus1Hour.toString)))
      ),
      normalUserId -> baseProfile,
      linkedAndInvalidExpiredDate -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-dev")),
        FireCloudKeyValue(Some("linkExpireTime"), Some("expiration-dates-cant-be-words!")))
      )
    )

  override def getProfile(userInfo: UserInfo): Future[Option[Profile]] = {
    val profileWrapper = try {
      Option(Profile(ProfileWrapper(userInfo.id, mockKeyValues(userInfo.id).toList)))
    } catch {
      case e:NoSuchElementException => None
    }
    Future.successful(profileWrapper)
  }

  override def saveProfile(userInfo: UserInfo, profile: BasicProfile): Future[Unit] = {
    saveKeyValues(userInfo, profile.propertyValueMap).map(_ => ())
  }

  override def saveKeyValues(userInfo: UserInfo, keyValues: Map[String, String]): Future[Try[Unit]] = {
    val newKVsForUser = (userInfo.id -> (mockKeyValues(userInfo.id) ++ keyValues.map { case (key, value) => FireCloudKeyValue(Option(key), Option(value))}))
    mockKeyValues = mockKeyValues + newKVsForUser
    Future.successful(Success(()))

  }

  override def getAllUserValuesForKey(key: String): Future[Map[String, String]] = {
    val userValuesForKey = mockKeyValues.map{ case (userId, keyValues) =>
      userId -> keyValues.filter(_.key.equals(Option(key)))
    }.flatMap { case (userId, kvPair) =>
      if(kvPair.nonEmpty) {
        Some((userId -> kvPair.head.value.get))
      }
      else None
    }

    Future.successful(userValuesForKey)
  }

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(true))

}
