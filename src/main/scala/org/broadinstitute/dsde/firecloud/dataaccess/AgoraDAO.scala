package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, Method}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.Future

object AgoraDAO {
  lazy val serviceName = "Agora"
}

trait AgoraDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource(AgoraDAO.serviceName)

  def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]]
  def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]]

  def postMethod(ns: String, name: String, synopsis: String, documentation: String, payload: String)(implicit userInfo: UserInfo): Future[Method]
  def redactMethod(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[Unit]

  def getMethodPermissions(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[List[AgoraPermission]]
  def postMethodPermissions(ns: String, name: String, snapshotId: Int, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]]
}
