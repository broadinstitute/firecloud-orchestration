package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.MethodRepository.AgoraPermission
import org.broadinstitute.dsde.firecloud.model.UserInfo

import scala.concurrent.Future

class MockAgoraDAO extends AgoraDAO {
  override def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = ???

  override def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = ???
}
