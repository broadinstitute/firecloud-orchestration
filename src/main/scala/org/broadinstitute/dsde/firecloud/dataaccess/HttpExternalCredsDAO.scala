package org.broadinstitute.dsde.firecloud.dataaccess

import bio.terra.externalcreds.api.OauthApi
import bio.terra.externalcreds.api.AdminApi
import bio.terra.externalcreds.client.ApiClient
import bio.terra.externalcreds.model.Provider
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.joda.time.DateTime
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestTemplate

import scala.jdk.CollectionConverters._

class HttpExternalCredsDAO(enabled: Boolean) extends ExternalCredsDAO {

  override def isEnabled: Boolean = enabled

  override def getLinkedAccount(implicit userInfo: UserInfo): Option[LinkedEraAccount] = {
    val oauthApi: OauthApi = getOauthApi(userInfo.accessToken.token)
    val linkInfoWithHttpInfo = oauthApi.getLinkWithHttpInfo(Provider.ERA_COMMONS)
    if (linkInfoWithHttpInfo.getStatusCode.isSameCodeAs(HttpStatusCode.valueOf(404))) {
      Option.empty
    } else {
      val linkInfo = linkInfoWithHttpInfo.getBody
      Some(LinkedEraAccount(userInfo.id, linkInfo.getExternalUserId, new DateTime(linkInfo.getExpirationTimestamp)))
    }
  }

  override def getLinkedEraAccountForUsername(username: String)(implicit orchInfo: UserInfo): Option[LinkedEraAccount] = {
    val adminApi = getAdminApi(orchInfo.accessToken.token)
    val adminLinkInfoWithHttpInfo = adminApi.getLinkedAccountForExternalIdWithHttpInfo(Provider.ERA_COMMONS, username)
    if (adminLinkInfoWithHttpInfo.getStatusCode.isSameCodeAs(HttpStatusCode.valueOf(404))) {
      Option.empty
    } else {
      val adminLinkInfo = adminLinkInfoWithHttpInfo.getBody
      Some(LinkedEraAccount(adminLinkInfo.getUserId, adminLinkInfo.getLinkedExternalId, new DateTime(adminLinkInfo.getLinkExpireTime)))
    }
  }

  override def getActiveLinkedEraAccounts(implicit orchInfo: UserInfo): Seq[LinkedEraAccount] = {
    val adminApi = getAdminApi(orchInfo.accessToken.token)
    val adminLinkInfos = adminApi.getActiveLinkedAccounts(Provider.ERA_COMMONS)
    adminLinkInfos.asScala.map { linkedAccount =>
      LinkedEraAccount(linkedAccount.getUserId, linkedAccount.getLinkedExternalId, new DateTime(linkedAccount.getLinkExpireTime))
    }.toSeq
  }

  private def getApi(accessToken: String): ApiClient = {
    val restTemplate = new RestTemplate
    val client = new ApiClient(restTemplate)
    client.setBasePath(FireCloudConfig.ExternalCreds.baseUrl)
    client.setAccessToken(accessToken)
    client
  }

  private def getOauthApi(accessToken: String): OauthApi = {
    val client = getApi(accessToken)
    new OauthApi(client)
  }

  private def getAdminApi(accessToken: String): AdminApi = {
    val client = getApi(accessToken)
    new AdminApi(client)
  }

}
