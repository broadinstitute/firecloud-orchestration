package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{ImportServiceListResponse, UserInfo}

import java.util.UUID

class MockCwdsDAO extends CwdsDAO {
  override def listJobsV1(workspaceId: UUID, runningOnly: Boolean)(implicit userInfo: UserInfo)
  : List[ImportServiceListResponse] = List()
}
