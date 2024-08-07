package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{LinkedEraAccount, UserInfo, WithAccessToken}
import org.databiosphere.workspacedata.client.ApiException

import scala.concurrent.Future

trait ExternalCredsDAO {

  @throws(classOf[ApiException])
  def getLinkedAccount(implicit userInfo: UserInfo): Future[Option[LinkedEraAccount]]

  @throws(classOf[ApiException])
  def putLinkedEraAccount(linkedEraAccount: LinkedEraAccount)(implicit orchInfo: WithAccessToken): Future[Unit]

  @throws(classOf[ApiException])
  def deleteLinkedEraAccount(userInfo: UserInfo)(implicit orchInfo: WithAccessToken): Future[Unit]

  @throws(classOf[ApiException])
  def getLinkedEraAccountForUsername(username: String)(implicit orchInfo: WithAccessToken): Future[Option[LinkedEraAccount]]

  @throws(classOf[ApiException])
  def getActiveLinkedEraAccounts(implicit orchInfo: WithAccessToken): Future[Seq[LinkedEraAccount]]

}
