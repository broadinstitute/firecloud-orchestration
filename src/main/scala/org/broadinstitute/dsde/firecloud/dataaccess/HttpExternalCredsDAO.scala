package org.broadinstitute.dsde.firecloud.dataaccess

import bio.terra.externalcreds.api.OauthApi
import bio.terra.externalcreds.api.AdminApi
import bio.terra.externalcreds.client.ApiClient
import bio.terra.externalcreds.model.Provider
import com.google.api.client.http.HttpStatusCodes
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.LinkedEraAccount.unapply
import org.broadinstitute.dsde.firecloud.model.{LinkedEraAccount, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.workbench.model.WorkbenchException
import org.joda.time.DateTime
import org.springframework.http.{HttpStatus, HttpStatusCode}
import org.springframework.web.client.{HttpClientErrorException, RestTemplate}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class HttpExternalCredsDAO(implicit val executionContext: ExecutionContext) extends ExternalCredsDAO {

  override def getLinkedAccount(implicit userInfo: UserInfo): Future[Option[LinkedEraAccount]] = Future {
    val oauthApi: OauthApi = getOauthApi(userInfo.accessToken.token)
    try {
      val linkInfo = oauthApi.getLink(Provider.ERA_COMMONS)
      Some(LinkedEraAccount(userInfo.id, linkInfo.getExternalUserId, new DateTime(linkInfo.getExpirationTimestamp)))
    } catch {
      case e: HttpClientErrorException =>
        e.getStatusCode.value() match {
          case HttpStatusCodes.STATUS_CODE_NOT_FOUND => None
          case _ => throw new WorkbenchException(s"Failed to GET eRA Linked Account: ${e.getMessage}")
        }
    }
  }

  override def putLinkedEraAccount(linkedEraAccount: LinkedEraAccount)(implicit orchInfo: WithAccessToken): Future[Unit] = Future {
    val adminApi = getAdminApi(orchInfo.accessToken.token)
    adminApi.putLinkedAccountWithFakeToken(unapply(linkedEraAccount), Provider.ERA_COMMONS)
  }

  override def deleteLinkedEraAccount(userInfo: UserInfo)(implicit orchInfo: WithAccessToken): Future[Unit] = Future {
    val adminApi = getAdminApi(orchInfo.accessToken.token)
    adminApi.adminDeleteLinkedAccount(userInfo.id, Provider.ERA_COMMONS)
  }

  override def getLinkedEraAccountForUsername(username: String)(implicit orchInfo: WithAccessToken): Future[Option[LinkedEraAccount]] =  Future {
    val adminApi = getAdminApi(orchInfo.accessToken.token)
    try {
      val adminLinkInfo = adminApi.getLinkedAccountForExternalId(Provider.ERA_COMMONS, username)
      Some(LinkedEraAccount(adminLinkInfo))
    } catch {
      case e: HttpClientErrorException =>
        e.getStatusCode.value() match {
          case HttpStatusCodes.STATUS_CODE_NOT_FOUND => None
          case _ => throw new WorkbenchException(s"Failed to GET eRA Linked Account for username: ${e.getMessage}")
        }
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
