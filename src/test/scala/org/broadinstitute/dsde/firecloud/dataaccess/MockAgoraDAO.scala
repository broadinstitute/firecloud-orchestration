package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.MethodRepository.{ACLNames, AgoraPermission}
import org.broadinstitute.dsde.firecloud.model.UserInfo

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

  override def status: Future[(Boolean, Option[String])] = Future((true, None))

}
