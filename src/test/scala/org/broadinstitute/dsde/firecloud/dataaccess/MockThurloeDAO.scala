package org.broadinstitute.dsde.firecloud.dataaccess

import java.util.NoSuchElementException

import org.broadinstitute.dsde.firecloud.model.Trial.{TrialStates, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, FireCloudKeyValue, Profile, ProfileWrapper, Trial, UserInfo}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Try}

/**
 * Created by mbemis on 10/25/16.
 *
 */
class MockThurloeDAO extends ThurloeDAO {

  val NORMAL_USER = "normal-user"

  val TCGA_LINKED = "tcga-linked"
  val TCGA_LINKED_NO_EXPIRE_DATE = "tcga-linked-no-expire-date"
  val TCGA_LINKED_EXPIRED = "tcga-linked-expired"
  val TCGA_LINKED_INVALID_EXPIRE_DATE = "tcga-linked-user-invalid-expire-date"
  val TCGA_UNLINKED = "tcga-unlinked"

  val TARGET_LINKED = "target-linked"
  val TARGET_LINKED_EXPIRED = "target-linked-expired"
  val TARGET_UNLINKED = "target-unlinked"

  val TCGA_AND_TARGET_LINKED = "tcga-and-target-linked"
  val TCGA_AND_TARGET_LINKED_EXPIRED = "tcga-and-target-linked-expired"
  val TCGA_AND_TARGET_UNLINKED = "tcga-and-target-unlinked"

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
      NORMAL_USER -> baseProfile,
      //TCGA users
      TCGA_LINKED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("tcga-user")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowPlus30Days.toString)))
      ),
      TCGA_LINKED_NO_EXPIRE_DATE -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user")))
      ),
      TCGA_LINKED_EXPIRED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user2")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowMinus1Hour.toString)))
      ),
      TCGA_LINKED_INVALID_EXPIRE_DATE -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-dev")),
        FireCloudKeyValue(Some("linkExpireTime"), Some("expiration-dates-cant-be-words!")))
      ),
      TCGA_UNLINKED -> baseProfile,
      //TARGET users
      TARGET_LINKED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("target-user")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowPlus30Days.toString)))
      ),
      TARGET_LINKED_EXPIRED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user2")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowMinus1Hour.toString)))
      ),
      TARGET_UNLINKED -> baseProfile,
      //TCGA and TARGET users
      TCGA_AND_TARGET_LINKED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-dev")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowPlus30Days.toString)))
      ),
      TCGA_AND_TARGET_LINKED_EXPIRED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user2")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowMinus1Hour.toString)))
      ),
      TCGA_AND_TARGET_UNLINKED -> baseProfile
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

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))

  override def getTrialStatus(userInfo: UserInfo) = Future.successful(Some(
    UserTrialStatus(userInfo.id, Some(TrialStates.Terminated), 0, 0, 0, 0
  )))

  override def saveTrialStatus(userInfo: UserInfo, trialStatus: Trial.UserTrialStatus): Future[Try[Unit]] =
    Future.successful(Success(()))
}
