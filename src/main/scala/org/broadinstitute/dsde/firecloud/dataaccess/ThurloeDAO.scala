package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, Profile, UserInfo}
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
  def saveKeyValues(userInfo: UserInfo, keyValues: Map[String, String]): Future[Try[Unit]]
  override def serviceName:String = ThurloeDAO.serviceName
}
