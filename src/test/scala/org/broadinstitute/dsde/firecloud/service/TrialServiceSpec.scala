package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.MockTrialDAO
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates._
import org.broadinstitute.dsde.firecloud.model.Trial.{TrialProject, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{Trial, WorkbenchUserInfo}
import org.broadinstitute.dsde.rawls.model.RawlsBillingProjectName
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.ExecutionContext

class TrialServiceSpec extends BaseServiceSpec with BeforeAndAfterEach with TrialServiceSupport {

  override val trialDao = new TrialServiceSpecTrialDao
  implicit protected val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val userInfo = WorkbenchUserInfo("foo", "bar")

  override protected def beforeEach() = {
    trialDao.claimCalled = false
  }

  "TrialService.buildEnableUserStatus" - {
    "should claim a ProjectRecord when starting from trial status with None state" in {
      buildEnableUserStatus(userInfo, UserTrialStatus("userid", None, true, 0,0,0,0,None))
      assert(trialDao.claimCalled)
    }
    "should claim a ProjectRecord when starting from Disabled" in {
      buildEnableUserStatus(userInfo, UserTrialStatus("userid", Some(Disabled), true, 0,0,0,0,None))
      assert(trialDao.claimCalled)
    }
    Seq(Enabled, Enrolled, Terminated) foreach { state =>
      s"should not claim a ProjectRecord when starting from $state" in {
        buildEnableUserStatus(userInfo, UserTrialStatus("userid", Some(state), true, 0,0,0,0,None))
        assert(!trialDao.claimCalled)
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

