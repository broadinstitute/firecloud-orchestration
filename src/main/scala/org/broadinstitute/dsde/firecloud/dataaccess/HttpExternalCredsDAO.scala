package org.broadinstitute.dsde.firecloud.dataaccess

import bio.terra.externalcreds.api.AdminApi
import bio.terra.externalcreds.client.ApiClient
import bio.terra.externalcreds.model.Provider
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.springframework.web.client.RestTemplate

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class HttpExternalCredsDAO(implicit val executionContext: ExecutionContext) extends ExternalCredsDAO {

  private lazy val restTemplate = new RestTemplate

  override def getVisas(provider: String,
                        userId: String,
                        issuer: String,
                        visaType: String,
                        orchInfo: WithAccessToken
  ): Future[Seq[AnyRef]] = Future {
    val adminApi = getAdminApi(orchInfo.accessToken.token)
    adminApi.getVisas(Provider.fromValue(provider), userId, issuer, visaType).asScala.toSeq
  }

  private def getApi(accessToken: String): ApiClient = {
    val client = new ApiClient(restTemplate)
    client.setBasePath(FireCloudConfig.ExternalCreds.baseUrl)
    client.setAccessToken(accessToken)
    client
  }

  private def getAdminApi(accessToken: String): AdminApi = {
    val client = getApi(accessToken)
    new AdminApi(client)
  }

}
