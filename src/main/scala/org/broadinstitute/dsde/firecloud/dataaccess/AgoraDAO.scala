package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.MethodRepository.AgoraPermission
import org.broadinstitute.dsde.firecloud.model.{ReportsSubsystemStatus, UserInfo}
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.Future

trait AgoraDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource("Agora")

  def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]]
  def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]]
}
