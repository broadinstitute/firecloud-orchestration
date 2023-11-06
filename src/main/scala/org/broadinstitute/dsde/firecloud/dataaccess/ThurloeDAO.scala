package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, ProfileWrapper, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.Future
import scala.util.Try

/**
 * Created by mbemis on 10/21/16.
 */
object ThurloeDAO {
  lazy val serviceName = "Thurloe"
}

trait ThurloeDAO extends LazyLogging with ReportsSubsystemStatus {

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource(ThurloeDAO.serviceName)

  def getAllKVPs(forUserId: String, callerToken: WithAccessToken): Future[Option[ProfileWrapper]]
  def getAllUserValuesForKey(key: String): Future[Map[String, String]]
  def saveProfile(userInfo: UserInfo, profile: BasicProfile): Future[Unit]

  /**
    * Save KVPs for myself - the KVPs will be saved to the same user that authenticates the call.
    * @param userInfo contains the userid for which to save KVPs and that user's auth token
    * @param keyValues the KVPs to save
    * @return success/failure of save
    */
  def saveKeyValues(userInfo: UserInfo, keyValues: Map[String, String]): Future[Try[Unit]]

  /**
    * Save KVPs for a different user - the KVPs will be saved to the "forUserId" user,
    * but the call to Thurloe will be authenticated as the "callerToken" user.
    * @param forUserId the userid of the user for which to save KVPs
    * @param callerToken auth token of the user making the call
    * @param keyValues the KVPs to save
    * @return success/failure of save
    */
  def saveKeyValues(forUserId: String, callerToken: WithAccessToken, keyValues: Map[String, String]): Future[Try[Unit]]

  def bulkUserQuery(userIds: List[String], keySelection: List[String]): Future[List[ProfileWrapper]]

  def deleteKeyValue(forUserId: String, keyName: String, callerToken: WithAccessToken): Future[Try[Unit]]

  override def serviceName:String = ThurloeDAO.serviceName
}
