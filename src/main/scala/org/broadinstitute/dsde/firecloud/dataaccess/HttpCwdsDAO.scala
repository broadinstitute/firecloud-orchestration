package org.broadinstitute.dsde.firecloud.dataaccess

import okhttp3.Protocol
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.HttpCwdsDAO.commonHttpClient
import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, AsyncImportResponse, ImportServiceListResponse, UserInfo}
import org.broadinstitute.dsde.rawls.model.WorkspaceName
import org.databiosphere.workspacedata.api.{ImportApi, JobApi}
import org.databiosphere.workspacedata.client.ApiClient
import org.databiosphere.workspacedata.model.{GenericJob, ImportRequest}
import org.databiosphere.workspacedata.model.GenericJob.StatusEnum._

import scala.jdk.CollectionConverters._
import java.util.UUID
import scala.concurrent.Future

object HttpCwdsDAO {
  // singleton common http client to prevent object thrashing
  private val commonHttpClient = new ApiClient().getHttpClient.newBuilder
    .protocols(List(Protocol.HTTP_1_1).asJava)
    .build
}

class HttpCwdsDAO(enabled: Boolean, supportedFormats: List[String]) extends CwdsDAO {

  private final val RUNNING_STATUSES: java.util.List[String] = List("CREATED", "QUEUED", "RUNNING").asJava

  private final val STATUS_TRANSLATION: Map[GenericJob.StatusEnum,String] = Map(
    // there is no effective difference between Translating and ReadyForUpsert for our purposes
    CREATED -> "Translating",
    QUEUED -> "Translating",
    RUNNING -> "ReadyForUpsert",
    SUCCEEDED -> "Done",
    ERROR -> "Error",
    CANCELLED -> "Error",
    UNKNOWN -> "Error"
  )

  override def isEnabled: Boolean = enabled

  override def getSupportedFormats: List[String] = supportedFormats

  override def listJobsV1(workspaceId: String, runningOnly: Boolean)(implicit userInfo: UserInfo)
  : scala.collection.immutable.List[ImportServiceListResponse] = {
    // determine the proper cWDS statuses based on the runningOnly argument
    // the Java API expects null when not specifying statuses
    val statuses = if (runningOnly) RUNNING_STATUSES else null

    // prepare the cWDS client
    val apiClient: ApiClient = new ApiClient()
    apiClient.setHttpClient(commonHttpClient)
    apiClient.setBasePath(FireCloudConfig.Cwds.baseUrl)
    apiClient.setBearerToken(userInfo.accessToken.token)
    val jobApi: JobApi = new JobApi()
    jobApi.setApiClient(apiClient)

    // query cWDS for its jobs, and translate the response to ImportServiceListResponse format
    jobApi.jobsInInstanceV1(UUID.fromString(workspaceId), statuses)
      .asScala
      .map(toImportServiceListResponse)
      .toList
  }

  override def importV1(workspaceId: String,
                        importRequest: AsyncImportRequest
                       )(implicit userInfo: UserInfo): GenericJob = {
    // prepare the cWDS client
    val apiClient: ApiClient = new ApiClient()
    apiClient.setHttpClient(commonHttpClient)
    apiClient.setBasePath(FireCloudConfig.Cwds.baseUrl)
    apiClient.setAccessToken(userInfo.accessToken.token)
    val importApi: ImportApi = new ImportApi()
    importApi.setApiClient(apiClient)

    // TODO: create import request object
    val importRequest: ImportRequest = new ImportRequest

    importApi.importV1(importRequest, UUID.fromString(workspaceId))
  }

  protected[dataaccess] def toImportServiceListResponse(cwdsJob: GenericJob): ImportServiceListResponse = {
    ImportServiceListResponse(jobId = cwdsJob.getJobId.toString,
      status = toImportServiceStatus(cwdsJob.getStatus),
      filetype = cwdsJob.getJobType.getValue,
      message = Option(cwdsJob.getErrorMessage))
  }

  protected[dataaccess] def toImportServiceStatus(cwdsStatus: GenericJob.StatusEnum): String = {
    // don't fail status translation if status somehow could not be found
    STATUS_TRANSLATION.getOrElse(cwdsStatus, "Unknown")
  }


}
