package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, ImportServiceListResponse, UserInfo}
import org.databiosphere.workspacedata.model.GenericJob
import org.databiosphere.workspacedata.model.GenericJob.{JobTypeEnum, StatusEnum}

import java.time.OffsetDateTime
import java.util.UUID

class MockCwdsDAO extends CwdsDAO {

  override def isEnabled: Boolean = true
  override def listJobsV1(workspaceId: String, runningOnly: Boolean)(implicit userInfo: UserInfo)
  : List[ImportServiceListResponse] = List()

  override def importV1(workspaceId: String,
                        importRequest: AsyncImportRequest
                       )(implicit userInfo: UserInfo): GenericJob = {
    val genericJob: GenericJob = new GenericJob
    genericJob.setJobId(UUID.randomUUID())
    genericJob.setStatus(StatusEnum.RUNNING)
    genericJob.setJobType(JobTypeEnum.DATA_IMPORT)
    genericJob.setInstanceId(UUID.fromString(workspaceId)) // will this cause a problem in tests? Some test data has non-UUIDs.
    genericJob.setCreated(OffsetDateTime.now())
    genericJob.setUpdated(OffsetDateTime.now())

    genericJob
  }
}
