package org.broadinstitute.dsde.firecloud.dataaccess

import okhttp3.Protocol
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException}
import org.broadinstitute.dsde.firecloud.dataaccess.HttpCwdsDAO.commonHttpClient
import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, ImportServiceListResponse, UserInfo}
import org.databiosphere.workspacedata.api.{ImportApi, JobApi}
import org.databiosphere.workspacedata.client.ApiClient
import org.databiosphere.workspacedata.model.{GenericJob, ImportRequest}
import org.databiosphere.workspacedata.model.GenericJob.StatusEnum._

import java.net.URI
import scala.jdk.CollectionConverters._
import java.util.UUID

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

  private final val TYPE_TRANSLATION: Map[String, ImportRequest.TypeEnum] = Map(
    "pfb" -> ImportRequest.TypeEnum.PFB,
    "tdrexport" -> ImportRequest.TypeEnum.TDRMANIFEST,
    "rawlsjson" -> ImportRequest.TypeEnum.RAWLSJSON
  )

  override def isEnabled: Boolean = enabled

  override def getSupportedFormats: List[String] = supportedFormats

  override def listJobsV1(workspaceId: String, runningOnly: Boolean)(implicit userInfo: UserInfo)
  : scala.collection.immutable.List[ImportServiceListResponse] = {
    // determine the proper cWDS statuses based on the runningOnly argument
    // the Java API expects null when not specifying statuses
    val statuses = if (runningOnly) RUNNING_STATUSES else null

    val jobApi: JobApi = new JobApi()
    jobApi.setApiClient(getApiClient(userInfo.accessToken.token))

    // query cWDS for its jobs, and translate the response to ImportServiceListResponse format
    jobApi.jobsInInstanceV1(UUID.fromString(workspaceId), statuses)
      .asScala
      .map(toImportServiceListResponse)
      .toList
  }

  override def getJobV1(workspaceId: String, jobId: String)(implicit userInfo: UserInfo): ImportServiceListResponse = {
    val jobApi: JobApi = new JobApi()
    jobApi.setApiClient(getApiClient(userInfo.accessToken.token))

    toImportServiceListResponse(jobApi.jobStatusV1(UUID.fromString(jobId)))
  }

  override def importV1(workspaceId: String,
                        asyncImportRequest: AsyncImportRequest
                       )(implicit userInfo: UserInfo): GenericJob = {
    val importApi: ImportApi = new ImportApi()
    importApi.setApiClient(getApiClient(userInfo.accessToken.token))

    importApi.importV1(toCwdsImportRequest(asyncImportRequest), UUID.fromString(workspaceId))
  }

  protected[dataaccess] def toCwdsImportRequest(asyncImportRequest: AsyncImportRequest): ImportRequest = {
    val importRequest: ImportRequest = new ImportRequest
    importRequest.setUrl(URI.create(asyncImportRequest.url))
    importRequest.setType(toCwdsImportType(asyncImportRequest.filetype))

    // as of this writing, the only available option is "tdrSyncPermissions"
    asyncImportRequest.options.map { opts =>
      opts.tdrSyncPermissions.map { tdrSyncPermissions =>
        importRequest.setOptions(Map[String, Object]("tdrSyncPermissions" -> tdrSyncPermissions.asInstanceOf[Object]).asJava)
      }
      opts.isUpsert.map { isUpsert =>
        importRequest.setOptions(Map[String, Object]("isUpsert" -> isUpsert.asInstanceOf[Object]).asJava)

      }
    }

    importRequest
  }

  protected[dataaccess] def toCwdsImportType(input: String): ImportRequest.TypeEnum = {
    TYPE_TRANSLATION.getOrElse(input,
      throw new FireCloudException("Import type unknown; possible values are: " + TYPE_TRANSLATION.keys.mkString))
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

  private def getApiClient(accessToken: String): ApiClient = {
    // prepare the cWDS client
    val apiClient: ApiClient = new ApiClient()
    apiClient.setHttpClient(commonHttpClient)
    apiClient.setBasePath(FireCloudConfig.Cwds.baseUrl)
    apiClient.setBearerToken(accessToken)

    apiClient
  }

}
