package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.MethodRepository.{ACLNames, AgoraPermission, EntityAccessControlAgora, Method}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object MockAgoraDAO {
  def agoraPermission = AgoraPermission(
    user = Some("test-user@broadinstitute.org"),
    roles = Some(ACLNames.ListOwner)
  )
}

class MockAgoraDAO extends AgoraDAO {


  override def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    Future(List(MockAgoraDAO.agoraPermission))
  }

  override def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    Future(List(MockAgoraDAO.agoraPermission))
  }

  override def getMultiEntityPermissions(entityType: _root_.org.broadinstitute.dsde.firecloud.model.MethodRepository.AgoraEntityType.Value, entities: List[Method])(implicit userInfo: UserInfo) = {
    Future(List.empty[EntityAccessControlAgora])
  }

  def status: Future[SubsystemStatus] = {
    Future(SubsystemStatus(ok = true, None))
  }

  override def batchCreatePermissions(inputs: List[EntityAccessControlAgora])(implicit userInfo: UserInfo): Future[List[EntityAccessControlAgora]] = ???

  override def getPermission(url: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = ???

  override def createPermission(url: String, agoraPermissions: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = ???
}
