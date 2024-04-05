package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.dataaccess.CwdsDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class DisabledServiceFactoryTest extends AnyFreeSpec with Matchers {
  "DisabledServiceFactory" - {
    "newDisabledService" - {
      "should return a new instance of the service that throws UnsupportedOperationException for all methods except isEnabled" in {
        implicit val userInfo: UserInfo = null

        val disabledService = DisabledServiceFactory.newDisabledService[CwdsDAO]
        assertThrows[UnsupportedOperationException] {
          disabledService.getSupportedFormats
        }
        assertThrows[UnsupportedOperationException] {
          disabledService.listJobsV1("workspaceId", runningOnly = true)
        }
        assertThrows[UnsupportedOperationException] {
          disabledService.getJobV1("workspaceId", "jobId")
        }
        assertThrows[UnsupportedOperationException] {
          disabledService.importV1("workspaceId", null)
        }
      }

      "should return a new instance of the service that returns false for isEnabled" in {
        val disabledService = DisabledServiceFactory.newDisabledService[CwdsDAO]
        disabledService.isEnabled shouldBe false
      }
    }
  }
}
