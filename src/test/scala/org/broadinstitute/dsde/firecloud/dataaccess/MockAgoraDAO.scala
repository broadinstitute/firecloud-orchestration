package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{ACLNames, AgoraPermission, Method}
import org.broadinstitute.dsde.firecloud.model.{SubsystemStatus, UserInfo}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import spray.http.HttpResponse

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object MockAgoraDAO {
  val agoraPermission = AgoraPermission(
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

  override def getMethod(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[Method] = {
    Future(null)
  }

  override def postMethod(ns: String, name: String, synopsis: Option[String], documentation: Option[String], payload: String)(implicit userInfo: UserInfo): Future[Method] = {
    // based on what the unit test passed in, return success or failure
    (ns, name) match {
      case ("exceptions", "postError") => Future.failed(new AgoraException("postMethod"))
      case _ => Future(Method(
        namespace = Some(ns),
        name = Some(name),
        snapshotId = Some(2),
        synopsis = synopsis,
        documentation = documentation,
        payload = Some(payload),
        createDate = Some("01-01-1970"),
        entityType = Some("Workflow")
      ))
    }
  }

  override def redactMethod(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[HttpResponse] = {
    (ns, name) match {
      case ("exceptions", "redactError") => Future.failed(new AgoraException("redactMethod"))
      case _ => Future(HttpResponse())
    }
  }

  override def getMethodPermissions(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    (ns, name) match {
      case ("exceptions", "permGetError") => Future.failed(new AgoraException("getMethodPermissions"))
      case _ => Future(List(MockAgoraDAO.agoraPermission))
    }
  }

  override def postMethodPermissions(ns: String, name: String, snapshotId: Int, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    (ns, name) match {
      case ("exceptions", "permPostError") => Future.failed(new AgoraException("postMethodPermissions"))
      case _ => Future(perms)
    }
  }

  def status: Future[SubsystemStatus] = {
    Future(throw new Exception("Agora Mock DAO exception"))
  }

}
