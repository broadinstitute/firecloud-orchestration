package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, Method}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import spray.http.HttpResponse

import scala.concurrent.Future

class AgoraException(val method: String, val innerException: Exception = null) extends FireCloudException

object AgoraDAO {
  lazy val serviceName = "Agora"
}

trait AgoraDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource(AgoraDAO.serviceName)

  def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]]
  def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]]

  def getMethod(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[Method]
  def postMethod(ns: String, name: String, synopsis: Option[String], documentation: Option[String], payload: String)(implicit userInfo: UserInfo): Future[Method]
  def redactMethod(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[HttpResponse]

  def getMethodPermissions(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[List[AgoraPermission]]
  def postMethodPermissions(ns: String, name: String, snapshotId: Int, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]]
}
