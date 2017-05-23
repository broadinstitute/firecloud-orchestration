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

  override def postMethod(ns: String, name: String, synopsis: String, documentation: String, payload: String)(implicit userInfo: UserInfo): Future[Method] = {
    // based on what the unit test passed in, return success or failure
    (ns, name) match {
      case ("exceptions", "postError") => throw new FireCloudExceptionWithErrorReport(ErrorReport("intentional error for unit tests"))
      case _ => Future(Method(
        namespace = Some(ns),
        name = Some(name),
        snapshotId = Some(2),
        synopsis = Some(synopsis),
        documentation = Some(documentation),
        payload = Some(payload),
        createDate = Some("01-01-1970"),
        entityType = Some("Workflow")
      ))
    }
  }

  override def redactMethod(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[HttpResponse] = {
    (ns, name) match {
      case ("exception", "redactError") => throw new FireCloudExceptionWithErrorReport(ErrorReport("intentional error for unit tests"))
      case _ => Future(HttpResponse())
    }
  }

  override def getMethodPermissions(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    (ns, name) match {
      case ("exceptions", "permGetError") => throw new FireCloudExceptionWithErrorReport(ErrorReport("intentional error for unit tests"))
      case _ => Future(List(MockAgoraDAO.agoraPermission))
    }
  }

  override def postMethodPermissions(ns: String, name: String, snapshotId: Int, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    (ns, name) match {
      case ("exceptions", "permPostError") => throw new FireCloudExceptionWithErrorReport(ErrorReport("intentional error for unit tests"))
      case _ => Future(perms)
    }
  }

  def status: Future[SubsystemStatus] = {
    Future(throw new Exception("Agora Mock DAO exception"))
  }

}
