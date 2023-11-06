package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.{AgoraEntityType, AgoraPermission, EntityAccessControlAgora, Method}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.Future

object AgoraDAO {
  lazy val serviceName = "Agora"
}

trait AgoraDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource(AgoraDAO.serviceName)

  def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]]
  def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]]

  def getMultiEntityPermissions(entityType: AgoraEntityType.Value, entities: List[Method])(implicit userInfo: UserInfo): Future[List[EntityAccessControlAgora]]

  def batchCreatePermissions(inputs: List[EntityAccessControlAgora])(implicit userInfo: UserInfo): Future[List[EntityAccessControlAgora]]
  def getPermission(url: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]]
  def createPermission(url: String,  agoraPermissions: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]]

  override def serviceName:String = AgoraDAO.serviceName
}
