package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{LinkedEraAccount, UserInfo, WithAccessToken}

import scala.concurrent.Future

class DisabledExternalCredsDAO extends ExternalCredsDAO {

  override def getLinkedAccount(implicit userInfo: UserInfo): Future[Option[LinkedEraAccount]] = Future.successful(None)

  override def putLinkedEraAccount(linkedEraAccount: LinkedEraAccount)(implicit orchInfo: WithAccessToken): Future[Unit] = Future.unit

  override def deleteLinkedEraAccount(userInfo: UserInfo)(implicit orchInfo: WithAccessToken): Future[Unit] = Future.unit

  override def getLinkedEraAccountForUsername(username: String)(implicit orchInfo: WithAccessToken): Future[Option[LinkedEraAccount]] = Future.successful(None)

  override def getActiveLinkedEraAccounts(implicit orchInfo: WithAccessToken): Future[Seq[LinkedEraAccount]] = Future.successful(Seq.empty)
}
