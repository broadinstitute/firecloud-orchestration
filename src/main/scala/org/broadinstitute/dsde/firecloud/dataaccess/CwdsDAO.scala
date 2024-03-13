package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{ImportServiceListResponse, UserInfo}

import java.util.UUID
import scala.concurrent.Future

trait CwdsDAO {

  def listJobsV1(workspaceId: String,
                 runningOnly: Boolean
                )(implicit userInfo: UserInfo): List[ImportServiceListResponse]
}
