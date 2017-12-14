package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.MockTrialDAO
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates._
import org.broadinstitute.dsde.firecloud.model.Trial.{TrialProject, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{Trial, WorkbenchUserInfo}
import org.broadinstitute.dsde.rawls.model.RawlsBillingProjectName
import org.scalatest.BeforeAndAfterEach

class TrialServiceSpec extends BaseServiceSpec with BeforeAndAfterEach with TrialServiceSupport {

  val trialDAO = new TrialServiceSpecTrialDao

  val userInfo = WorkbenchUserInfo("foo", "bar")

  override protected def beforeEach() = {
    trialDAO.claimCalled = false
  }

  "TrialService.buildEnableUserStatus" - {
    "should claim a ProjectRecord when starting from None trial status" in {
      buildEnableUserStatus(userInfo, None)
      assert(trialDAO.claimCalled)
    }
    "should claim a ProjectRecord when starting from trial status with None state" in {
      buildEnableUserStatus(userInfo, Some(UserTrialStatus("userid", None, true, 0,0,0,0,None)))
      assert(trialDAO.claimCalled)
    }
    "should claim a ProjectRecord when starting from Disabled" in {
      buildEnableUserStatus(userInfo, Some(UserTrialStatus("userid", Some(Disabled), true, 0,0,0,0,None)))
      assert(trialDAO.claimCalled)
    }
    Seq(Enabled, Enrolled, Terminated) foreach { state =>
      s"should not claim a ProjectRecord when starting from $state" in {
        buildEnableUserStatus(userInfo, Some(UserTrialStatus("userid", Some(state), true, 0,0,0,0,None)))
        assert(!trialDAO.claimCalled)
      }
    }
  }
}

class TrialServiceSpecTrialDao extends MockTrialDAO {
  var claimCalled = false

  override def claimProjectRecord(userInfo: WorkbenchUserInfo, randomizationFactor: Int = 20): Trial.TrialProject = {
    claimCalled = true
    TrialProject(RawlsBillingProjectName("fake"))
  }
}

