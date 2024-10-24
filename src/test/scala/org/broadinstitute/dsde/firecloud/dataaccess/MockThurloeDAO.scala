package org.broadinstitute.dsde.firecloud.dataaccess

import java.util.NoSuchElementException

import org.broadinstitute.dsde.firecloud.dataaccess.MockThurloeDAO._
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, FireCloudKeyValue, ProfileWrapper, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Try}

/**
 * Created by mbemis on 10/25/16.
 *
 */
object MockThurloeDAO {
  val NORMAL_USER = "normal-user"
}

class MockThurloeDAO extends ThurloeDAO {

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

  val HAVE_GOOGLE_GROUP = "have-google-group"
  val HAVE_EMPTY_GOOGLE_GROUP = "have-empty-google-group"
  val NO_CONTACT_EMAIL = "no-contact-email"

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
      "foo" -> baseProfile, // to match registeredUser, enabledUserInfo, unknownUserInfo from MockSamDao
      //TCGA users
      TCGA_LINKED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TCGA_LINKED)),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("tcga-user")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowPlus30Days.toString)))
      ),
      TCGA_LINKED_NO_EXPIRE_DATE -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TCGA_LINKED_NO_EXPIRE_DATE)),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user")))
      ),
      TCGA_LINKED_EXPIRED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TCGA_LINKED_EXPIRED)),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user2")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowMinus1Hour.toString)))
      ),
      TCGA_LINKED_INVALID_EXPIRE_DATE -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TCGA_LINKED_INVALID_EXPIRE_DATE)),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-dev")),
        FireCloudKeyValue(Some("linkExpireTime"), Some("expiration-dates-cant-be-words!")))
      ),
      TCGA_UNLINKED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TCGA_UNLINKED)))
      ),
      //TARGET users
      TARGET_LINKED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TARGET_LINKED)),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("target-user")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowPlus30Days.toString)))
      ),
      TARGET_LINKED_EXPIRED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TARGET_LINKED_EXPIRED)),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user2")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowMinus1Hour.toString)))
      ),
      TARGET_UNLINKED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TARGET_UNLINKED)))
      ),
      //TCGA and TARGET users
      TCGA_AND_TARGET_LINKED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TCGA_AND_TARGET_LINKED)),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-dev")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowPlus30Days.toString)))
      ),
      TCGA_AND_TARGET_LINKED_EXPIRED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TCGA_AND_TARGET_LINKED_EXPIRED)),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user2")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowMinus1Hour.toString)))
      ),
      TCGA_AND_TARGET_UNLINKED -> baseProfile.++(Set(
        FireCloudKeyValue(Some("email"), Some(TCGA_AND_TARGET_UNLINKED)))
      ),
      //Anonymized google groups
      HAVE_GOOGLE_GROUP -> baseProfile.++(Set(
        FireCloudKeyValue(Some("anonymousGroup"), Some("existing-google-group@support.something.firecloud.org")))
      ),
      HAVE_EMPTY_GOOGLE_GROUP -> baseProfile.++(Set(
        FireCloudKeyValue(Some("anonymousGroup"), Some("")))
      ),
      NO_CONTACT_EMAIL -> baseProfile.++(Set(
        FireCloudKeyValue(Some("contactEmail"), Some("")))
      )
    )


  override def getAllKVPs(forUserId: String, callerToken: WithAccessToken): Future[Option[ProfileWrapper]] = {
    val profileWrapper = try {
      Option(ProfileWrapper(forUserId, mockKeyValues(forUserId).toList))
    } catch {
      case e:NoSuchElementException => None
    }
    Future.successful(profileWrapper)
  }

  override def saveProfile(userInfo: UserInfo, profile: BasicProfile): Future[Unit] = {
    saveKeyValues(userInfo, profile.propertyValueMap).map(_ => ())
  }

  override def saveKeyValues(userInfo: UserInfo, keyValues: Map[String, String]): Future[Try[Unit]] = {
    val newKVsForUser = userInfo.id -> (mockKeyValues(userInfo.id).filter {
      case FireCloudKeyValue(Some(key), _) => !keyValues.contains(key)
      case FireCloudKeyValue(_, _) => false
    } ++ keyValues.map { case (key, value) => FireCloudKeyValue(Option(key), Option(value))})

    mockKeyValues = mockKeyValues + newKVsForUser
    Future.successful(Success(()))
  }


  override def saveKeyValues(forUserId: String, callerToken: WithAccessToken, keyValues: Map[String, String]): Future[Try[Unit]] = {
    val newKVsForUser = (forUserId -> (mockKeyValues(forUserId) ++ keyValues.map { case (key, value) => FireCloudKeyValue(Option(key), Option(value))}))
    mockKeyValues = mockKeyValues + newKVsForUser
    Future.successful(Success(()))
  }

  override def deleteKeyValue(forUserId: String, keyName: String, callerToken: WithAccessToken): Future[Try[Unit]] = {
    val newKVsForUser = forUserId -> mockKeyValues(forUserId).filterNot(_.key.contains(keyName))
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

  override def bulkUserQuery(userIds: List[String], keySelection: List[String]): Future[List[ProfileWrapper]] = {

    val mockdata = userIds.map { forUserId =>
      if (mockKeyValues.contains(forUserId)) {
        val kvps = mockKeyValues(forUserId).filter(kvp => kvp.key.isDefined && keySelection.contains(kvp.key.get))
        Some(ProfileWrapper(forUserId, kvps.toList))
      } else {
        None
      }
    }
    Future.successful(mockdata.flatten)
  }
}
