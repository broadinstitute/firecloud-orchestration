package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{ImportServiceListResponse, UserInfo}
import org.databiosphere.workspacedata.api.JobApi
import org.databiosphere.workspacedata.client.ApiClient
import org.databiosphere.workspacedata.model.GenericJob
import org.databiosphere.workspacedata.model.GenericJob.StatusEnum._

import scala.jdk.CollectionConverters._
import java.util.UUID

class HttpCwdsDAO extends CwdsDAO {

  private final val RUNNING_STATUSES: java.util.List[String] = List("CREATED", "QUEUED", "RUNNING").asJava
  private final val ALL_STATUSES: java.util.List[String] = List.empty[String].asJava

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

  override def listJobsV1(workspaceId: UUID, runningOnly: Boolean)(implicit userInfo: UserInfo)
  : List[ImportServiceListResponse] = {
    // determine the proper cWDS statuses based on the runningOnly argument
    val statuses = if (runningOnly) RUNNING_STATUSES else ALL_STATUSES

    // prepare the cWDS client
    // TODO AJ-1602: reuse api client?
    val apiClient: ApiClient = new ApiClient()
    apiClient.setBasePath(FireCloudConfig.Cwds.baseUrl)
    apiClient.setAccessToken(userInfo.accessToken.token)
    val jobApi: JobApi = new JobApi()
    jobApi.setApiClient(apiClient)

    // query cWDS for its jobs, and translate the response to ImportServiceListResponse format
    jobApi.jobsInInstanceV1(workspaceId, statuses)
      .asScala
      .map(toImportServiceListResponse)
      .toList
  }

  private def toImportServiceListResponse(cwdsJob: GenericJob) = {
    ImportServiceListResponse(jobId = cwdsJob.getJobId.toString,
      status = toImportServiceStatus(cwdsJob.getStatus),
      filetype = cwdsJob.getJobType.getValue,
      message = Option(cwdsJob.getErrorMessage))
  }

  private def toImportServiceStatus(cwdsStatus: GenericJob.StatusEnum): String = {
    STATUS_TRANSLATION.getOrElse(cwdsStatus, "blah")
  }


}
