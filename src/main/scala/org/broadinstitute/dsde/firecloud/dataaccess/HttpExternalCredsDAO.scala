package org.broadinstitute.dsde.firecloud.dataaccess

import bio.terra.externalcreds.api.OauthApi
import bio.terra.externalcreds.api.AdminApi
import bio.terra.externalcreds.client.ApiClient
import bio.terra.externalcreds.model.Provider
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.LinkedEraAccount.unapply
import org.broadinstitute.dsde.firecloud.model.{LinkedEraAccount, UserInfo, WithAccessToken}
import org.joda.time.DateTime
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestTemplate

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class HttpExternalCredsDAO(implicit val executionContext: ExecutionContext) extends ExternalCredsDAO {

  override def getLinkedAccount(implicit userInfo: UserInfo): Future[Option[LinkedEraAccount]] = Future {
    val oauthApi: OauthApi = getOauthApi(userInfo.accessToken.token)
    val linkInfoWithHttpInfo = oauthApi.getLinkWithHttpInfo(Provider.ERA_COMMONS)
    if (linkInfoWithHttpInfo.getStatusCode.isSameCodeAs(HttpStatusCode.valueOf(404))) {
      Option.empty
    } else {
      val linkInfo = linkInfoWithHttpInfo.getBody
      Some(LinkedEraAccount(userInfo.id, linkInfo.getExternalUserId, new DateTime(linkInfo.getExpirationTimestamp)))
    }
  }

  override def putLinkedEraAccount(linkedEraAccount: LinkedEraAccount)(implicit orchInfo: WithAccessToken): Future[Unit] = Future {
    val adminApi = getAdminApi(orchInfo.accessToken.token)
    val resultWithHttpInfo = adminApi.putLinkedAccountWithFakeTokenWithHttpInfo(unapply(linkedEraAccount), Provider.ERA_COMMONS)
    if (!resultWithHttpInfo.getStatusCode.is2xxSuccessful) {
      throw new RuntimeException(s"Failed to PUT eRA Linked Account: ${resultWithHttpInfo.getBody}")
    }
  }

  override def deleteLinkedEraAccount(userInfo: UserInfo)(implicit orchInfo: WithAccessToken): Future[Unit] = Future {
    val adminApi = getAdminApi(orchInfo.accessToken.token)
    val resultWithHttpInfo = adminApi.adminDeleteLinkedAccountWithHttpInfo(userInfo.id, Provider.ERA_COMMONS)
    if (!resultWithHttpInfo.getStatusCode.is2xxSuccessful) {
      throw new RuntimeException(s"Failed to DELETE  eRA Linked Account: ${resultWithHttpInfo.getBody}")
    }
  }

  override def getLinkedEraAccountForUsername(username: String)(implicit orchInfo: WithAccessToken): Future[Option[LinkedEraAccount]] =  Future {
    val adminApi = getAdminApi(orchInfo.accessToken.token)
    val adminLinkInfoWithHttpInfo = adminApi.getLinkedAccountForExternalIdWithHttpInfo(Provider.ERA_COMMONS, username)
    if (adminLinkInfoWithHttpInfo.getStatusCode.isSameCodeAs(HttpStatusCode.valueOf(404))) {
      Option.empty
    } else {
      Some(LinkedEraAccount(adminLinkInfoWithHttpInfo.getBody))
    }
  }

  override def getActiveLinkedEraAccounts(implicit orchInfo: WithAccessToken): Future[Seq[LinkedEraAccount]] = Future {
    val adminApi = getAdminApi(orchInfo.accessToken.token)
    val adminLinkInfos = adminApi.getActiveLinkedAccounts(Provider.ERA_COMMONS)
    adminLinkInfos.asScala.map(LinkedEraAccount.apply).toSeq
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
