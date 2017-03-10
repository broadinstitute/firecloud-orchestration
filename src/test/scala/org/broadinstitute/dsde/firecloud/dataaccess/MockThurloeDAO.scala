package org.broadinstitute.dsde.firecloud.dataaccess

import java.util.NoSuchElementException

import org.broadinstitute.dsde.firecloud.model.{BasicProfile, FireCloudKeyValue, Profile, ProfileWrapper, UserInfo}
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

  var mockKeyValues: Map[String, Set[FireCloudKeyValue]] =
    Map(
      linkedUserId -> Set(
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
        FireCloudKeyValue(Some("nonProfitStatus"), Some("true")),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-dev")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowPlus30Days.toString))
      ),
      linkedAndNoExpiredUserId -> Set(
        FireCloudKeyValue(Some("firstName"), Some("Caleb")),
        FireCloudKeyValue(Some("lastName"), Some("Testerson")),
        FireCloudKeyValue(Some("title"), Some("Mr.")),
        FireCloudKeyValue(Some("contactEmail"), Some("caleb.testerson@example.com")),
        FireCloudKeyValue(Some("institute"), Some("Broad")),
        FireCloudKeyValue(Some("institutionalProgram"), Some("Something")),
        FireCloudKeyValue(Some("programLocationCity"), Some("Cambridge")),
        FireCloudKeyValue(Some("programLocationState"), Some("MA")),
        FireCloudKeyValue(Some("programLocationCountry"), Some("USA")),
        FireCloudKeyValue(Some("pi"), Some("Abby Testerson")),
        FireCloudKeyValue(Some("nonProfitStatus"), Some("true")),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user"))
      ),
      linkedButExpiredUserId -> Set(
        FireCloudKeyValue(Some("firstName"), Some("Realmona")),
        FireCloudKeyValue(Some("lastName"), Some("Testerson")),
        FireCloudKeyValue(Some("title"), Some("Ms.")),
        FireCloudKeyValue(Some("contactEmail"), Some("realmona.testerson@example.com")),
        FireCloudKeyValue(Some("institute"), Some("Broad")),
        FireCloudKeyValue(Some("institutionalProgram"), Some("Something")),
        FireCloudKeyValue(Some("programLocationCity"), Some("Cambridge")),
        FireCloudKeyValue(Some("programLocationState"), Some("MA")),
        FireCloudKeyValue(Some("programLocationCountry"), Some("USA")),
        FireCloudKeyValue(Some("pi"), Some("Abby Testerson")),
        FireCloudKeyValue(Some("nonProfitStatus"), Some("true")),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-user2")),
        FireCloudKeyValue(Some("linkExpireTime"), Some(DateUtils.nowMinus1Hour.toString))
      ),
      normalUserId -> Set(
        FireCloudKeyValue(Some("firstName"), Some("Abby")),
        FireCloudKeyValue(Some("lastName"), Some("Testerson")),
        FireCloudKeyValue(Some("title"), Some("Mrs.")),
        FireCloudKeyValue(Some("contactEmail"), Some("abby.testerson@example.com")),
        FireCloudKeyValue(Some("institute"), Some("Broad")),
        FireCloudKeyValue(Some("institutionalProgram"), Some("Something")),
        FireCloudKeyValue(Some("programLocationCity"), Some("Cambridge")),
        FireCloudKeyValue(Some("programLocationState"), Some("MA")),
        FireCloudKeyValue(Some("programLocationCountry"), Some("USA")),
        FireCloudKeyValue(Some("pi"), Some("Myself")),
        FireCloudKeyValue(Some("nonProfitStatus"), Some("true"))
      ),
      linkedAndInvalidExpiredDate -> Set(
        FireCloudKeyValue(Some("firstName"), Some("RealmonaJr")),
        FireCloudKeyValue(Some("lastName"), Some("Testerson")),
        FireCloudKeyValue(Some("title"), Some("Ms.")),
        FireCloudKeyValue(Some("contactEmail"), Some("realmonajr.testerson@example.com")),
        FireCloudKeyValue(Some("institute"), Some("Broad")),
        FireCloudKeyValue(Some("institutionalProgram"), Some("Something")),
        FireCloudKeyValue(Some("programLocationCity"), Some("Cambridge")),
        FireCloudKeyValue(Some("programLocationState"), Some("MA")),
        FireCloudKeyValue(Some("programLocationCountry"), Some("USA")),
        FireCloudKeyValue(Some("pi"), Some("Abby Testerson")),
        FireCloudKeyValue(Some("nonProfitStatus"), Some("true")),
        FireCloudKeyValue(Some("linkedNihUsername"), Some("firecloud-dev")),
        FireCloudKeyValue(Some("linkExpireTime"), Some("expiration-dates-cant-be-words!"))
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

  override def saveKeyValue(userInfo: UserInfo, key: String, value: String): Future[Try[Unit]] = {
    val newKVsForUser = (userInfo.id -> (mockKeyValues(userInfo.id).filterNot(_.key == Some(key)) + FireCloudKeyValue(Option(key), Option(value))))
    mockKeyValues = mockKeyValues + newKVsForUser
    Future.successful(Success(()))
  }

  override def saveProfile(userInfo: UserInfo, profile: BasicProfile): Future[Unit] = {
    Future.sequence(profile.propertyValueMap.map { case (key, value) => saveKeyValue(userInfo, key, value) }) map({ _.forall({ _ => true }) })
  }

  override def saveKeyValues(userInfo: UserInfo, keyValues: Map[String, String]): Future[Iterable[Try[Unit]]] = {
    Future.sequence(keyValues.map { case (key, value) => saveKeyValue(userInfo, key, value) })
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

}
