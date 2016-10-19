package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{ACLNames, AgoraPermission}
import org.broadinstitute.dsde.firecloud.model.UserInfo

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MockAgoraDAO extends AgoraDAO with LazyLogging {

  private val agoraPermission = AgoraPermission(
    user = Some("test-user@broadinstitute.org"),
    roles = Some(ACLNames.ListOwner)
  )

  override def getNamespacePermissions(ns: String, entity: String)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    logger.debug(s"Getting namespace permissions: namespace: $ns, entity: $entity")
    Future(List(agoraPermission))
  }

  override def postNamespacePermissions(ns: String, entity: String, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    logger.debug(s"Posting namespace permissions: namespace: $ns, entity: $entity")
    Future(List(agoraPermission))
  }

}
