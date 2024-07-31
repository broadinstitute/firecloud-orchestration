package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{LinkedEraAccount, UserInfo, WithAccessToken}

import scala.concurrent.{ExecutionContext, Future}

class MockExternalCredsDAO(implicit executionContext: ExecutionContext) extends HttpExternalCredsDAO{

  override def getLinkedAccount(implicit userInfo: UserInfo): Future[Option[LinkedEraAccount]] = Future(None)

  override def putLinkedEraAccount(linkedEraAccount: LinkedEraAccount)(implicit orchInfo: WithAccessToken): Future[Unit] = Future()

  override def deleteLinkedEraAccount(userInfo: UserInfo)(implicit orchInfo: WithAccessToken): Future[Unit] = Future()

  override def getLinkedEraAccountForUsername(username: String)(implicit orchInfo: WithAccessToken): Future[Option[LinkedEraAccount]] = Future(None)

  override def getActiveLinkedEraAccounts(implicit orchInfo: WithAccessToken): Future[Seq[LinkedEraAccount]] = Future(Seq.empty)
}
