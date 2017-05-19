package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.MethodRepository.{ACLNames, AgoraPermission, Method}
import org.broadinstitute.dsde.firecloud.model.{SubsystemStatus, UserInfo}
import spray.http.HttpResponse

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object MockAgoraDAO {
  def agoraPermission = AgoraPermission(
    user = Some("test-user@broadinstitute.org"),
    roles = Some(ACLNames.ListOwner)
  )

  def method = Method(
    namespace = Some("test-namespace"),
    name = Some("test-name"),
    snapshotId = Some(1),
    synopsis = Some("test-synopsis"),
    documentation = Some("test-documentation"),
    createDate = Some("01-01-1970"),
    entityType = Some("Workflow")
  )
}

class MockAgoraDAO extends AgoraDAO {

  override def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    Future(List(MockAgoraDAO.agoraPermission))
  }

  override def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    Future(List(MockAgoraDAO.agoraPermission))
  }

  override def postMethod(ns: String, name: String, synopsis: String, documentation: String, payload: String)(implicit userInfo: UserInfo): Future[Method] = {
    Future(MockAgoraDAO.method)
  }

  override def redactMethod(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[HttpResponse] = {
    Future(HttpResponse())
  }

  override def getMethodPermissions(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    Future(List(MockAgoraDAO.agoraPermission))
  }

  override def postMethodPermissions(ns: String, name: String, snapshotId: Int, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    Future(List(MockAgoraDAO.agoraPermission))
  }

  def status: Future[SubsystemStatus] = {
    Future(throw new Exception("Agora Mock DAO exception"))
  }

}
