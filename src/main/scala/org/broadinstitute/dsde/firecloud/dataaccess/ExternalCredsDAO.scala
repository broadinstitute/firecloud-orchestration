package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.databiosphere.workspacedata.client.ApiException
import org.joda.time.DateTime

trait ExternalCredsDAO {

  def isEnabled: Boolean

  case class LinkedEraAccount(userId: String, linkedExternalId: String, linkExpireTime: DateTime)

  @throws(classOf[ApiException])
  def getLinkedAccount(implicit userInfo: UserInfo): Option[LinkedEraAccount]

  @throws(classOf[ApiException])
  def getLinkedEraAccountForUsername(username: String)(implicit orchInfo: UserInfo): Option[LinkedEraAccount]

  @throws(classOf[ApiException])
  def getActiveLinkedEraAccounts(implicit orchInfo: UserInfo): Seq[LinkedEraAccount]

}
