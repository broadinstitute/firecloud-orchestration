package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.Trial.UserTrialStatus
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, Profile, UserInfo, WithAccessToken}
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

  implicit val errorReportSource = ErrorReportSource(ThurloeDAO.serviceName)

  def getProfile(userInfo: UserInfo): Future[Option[Profile]]
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

  // methods to work with free-trial objects
  /**
    * get the UserTrialStatus associated with a specific user.
    *
    * @param forUserId the subjectid of the user whose trial status to get
    * @param callerToken the OAuth token of the person making the API call
    * @return the trial status for the specified user, or None if trial status could not be determined.
    */
  def getTrialStatus(forUserId: String, callerToken: WithAccessToken): Future[UserTrialStatus]

  /**
    * set the UserTrialStatus for a specific user
    *
    * @param forUserId the subjectid of the user whose trial status to set
    * @param callerToken the OAuth token of the person making the API call
    * @param trialStatus the trial status to save for the specified user
    * @return success/failure of whether or not the status saved correctly
    */
  def saveTrialStatus(forUserId: String, callerToken: WithAccessToken, trialStatus: UserTrialStatus): Future[Try[Unit]]


  override def serviceName:String = ThurloeDAO.serviceName
}
