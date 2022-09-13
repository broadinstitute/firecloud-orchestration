package org.broadinstitute.dsde.firecloud.dataaccess

import akka.http.scaladsl.model.Uri
import org.broadinstitute.dsde.firecloud.mock.MockAgoraACLData
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.{ACLNames, AgoraPermission, EntityAccessControlAgora, Method}
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

  override def getMultiEntityPermissions(entityType: _root_.org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.AgoraEntityType.Value, entities: List[Method])(implicit userInfo: UserInfo) = {
    Future(List.empty[EntityAccessControlAgora])
  }

  def status: Future[SubsystemStatus] = {
    Future(SubsystemStatus(ok = true, None))
  }

  override def batchCreatePermissions(inputs: List[EntityAccessControlAgora])(implicit userInfo: UserInfo): Future[List[EntityAccessControlAgora]] =
    Future.successful(MockAgoraACLData.multiUpsertResponse)

  override def getPermission(url: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    val pathString = Uri(url).path.toString()

    val rawData = if (pathString.endsWith(MockAgoraACLData.standardPermsPath)) {
      MockAgoraACLData.standardAgora
    } else if (pathString.endsWith(MockAgoraACLData.withEdgeCasesPath)) {
      MockAgoraACLData.edgesAgora
    } else {
      List.empty
    }

    // methods endpoints return the mock data in reverse order - this way we can differentiate methods vs. configs
    if (pathString.startsWith("/api/v1/methods/")) {
      Future.successful(rawData.reverse)
    } else {
      Future.successful(rawData)
    }

  }

  override def createPermission(url: String, agoraPermissions: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {

    val pathString = Uri(url).path.toString()

    // carried over from the previous mockserver: methods returns a bad, unparsable response from Agora
    // this allows us to test orch's handling of bad responses
    val rawData = if (pathString.endsWith(MockAgoraACLData.standardPermsPath)) {
      if (pathString.startsWith("/api/v1/methods")) {
        MockAgoraACLData.edgesAgora
      } else {
        MockAgoraACLData.standardAgora
      }
    } else {
      List.empty
    }

    Future.successful(rawData)

  }
}
