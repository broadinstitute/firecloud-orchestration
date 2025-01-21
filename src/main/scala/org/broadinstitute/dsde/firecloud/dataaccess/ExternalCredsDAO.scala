package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{LinkedEraAccount, UserInfo, WithAccessToken}
import org.databiosphere.workspacedata.client.ApiException

import scala.concurrent.Future

trait ExternalCredsDAO {

  @throws(classOf[ApiException])
  def getLinkedAccount(userInfo: UserInfo): Future[Option[LinkedEraAccount]]

  @throws(classOf[ApiException])
  def putLinkedEraAccount(linkedEraAccount: LinkedEraAccount, orchInfo: WithAccessToken): Future[Unit]

  @throws(classOf[ApiException])
  def deleteLinkedEraAccount(userInfo: UserInfo, orchInfo: WithAccessToken): Future[Unit]

  @throws(classOf[ApiException])
  def getLinkedEraAccountForUsername(username: String, orchInfo: WithAccessToken): Future[Option[LinkedEraAccount]]

  @throws(classOf[ApiException])
  def getActiveLinkedEraAccounts(orchInfo: WithAccessToken): Future[Seq[LinkedEraAccount]]

  @throws(classOf[ApiException])
  def getVisas(provider: String,
               userId: String,
               issuer: String,
               visaType: String,
               orchInfo: WithAccessToken
  ): Future[Seq[AnyRef]]
}
