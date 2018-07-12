package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.{searchDAO, shareLogDAO}
import org.broadinstitute.dsde.firecloud.model.ShareLog
import org.broadinstitute.dsde.firecloud.model.ShareLog.Share
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

import scala.util.Try

class ElasticSearchShareLogDAOSpec extends FreeSpec with Matchers with BeforeAndAfterAll with LazyLogging {
  override def beforeAll = {
    // using the delete from search dao, because we don't have recreate in sharelog dao
    // this comment is a copy pasta - todo need to verify this
    searchDAO.recreateIndex()
    ElasticSearchShareLogDAOSpecFixtures.fixtureShares map { share =>
      shareLogDAO.logShare(share.userId, share.sharee, share.shareType)
    }
  }

  override def afterAll = {
    // using the delete from search dao because we don't have recreate in sharelog dao
    // todo need to confirm this
    searchDAO.deleteIndex()
  }

  "ElasticSearchShareLogDAO" - {
    "logShare" - {
      "should log a share and get it back successfully using the generated MD5 hash" in {
        val share = Share("fake2", "fake1@gmail.com", ShareLog.WORKSPACE)
        val loggedShare = shareLogDAO.logShare(share.userId, share.sharee, share.shareType)
        val check = shareLogDAO.getShare(share)
        assertResult(loggedShare) { check }
      }
      "should successfully log a duplicate share" in {
        val loggedShare = shareLogDAO.logShare("fake4", "fake3@gmail.com", ShareLog.WORKSPACE)
        val check = Try(shareLogDAO.logShare(loggedShare.userId, loggedShare.sharee, loggedShare.shareType))
        assert(check.isSuccess)
      }
    }
    // todo `setSize` in `getShares` is causing transient failures
    "getShares" in {
      "should get shares of all types for a user" in {
        val expected = ElasticSearchShareLogDAOSpecFixtures.fixtureShares
          .filter(s => s.userId.equals("fake1"))
          .sortBy(s => (s.sharee, s.shareType))
        val check = shareLogDAO.getShares("fake1").sortBy(s => (s.sharee, s.shareType))
        assertResult(expected.map(s => (s.userId, s.sharee, s.shareType))) { check.map(s => (s.userId, s.sharee, s.shareType)) }
      }
      "should get shares of a specific type and none others" in {
        val expected = ElasticSearchShareLogDAOSpecFixtures.fixtureShares
          .filter(s => s.userId.equals("fake1"))
          .filter(s => s.shareType.equals(ShareLog.GROUP))
          .sortBy(s => (s.sharee, s.shareType))
        val check = shareLogDAO.getShares("fake1", Some(ShareLog.GROUP)).sortBy(s => (s.sharee, s.shareType))
        assertResult(expected.map(s => (s.userId, s.sharee, s.shareType))) { check.map(s => (s.userId, s.sharee, s.shareType)) }
      }
    }
  }
}

object ElasticSearchShareLogDAOSpecFixtures {
  val fixtureShares: Seq[Share] = Seq(
    Share("fake1", "fake2@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake3@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake4@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake5@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake6@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake7@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake8@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake9@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake10@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fakea1@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fakea2@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fakea3@gmail.com", ShareLog.WORKSPACE),
    Share("fake2", "fake1@gmail.com", ShareLog.WORKSPACE),
    Share("fake2", "fake3@gmail.com", ShareLog.WORKSPACE),
    Share("fake2", "fake4@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake2@gmail.com", ShareLog.GROUP),
    Share("fake1", "fake3@gmail.com", ShareLog.GROUP),
    Share("fake1", "fake4@gmail.com", ShareLog.GROUP),
    Share("fake1", "fake5@gmail.com", ShareLog.GROUP),
    Share("fake1", "fake6@gmail.com", ShareLog.GROUP),
    Share("fake1", "fake7@gmail.com", ShareLog.GROUP),
    Share("fake1", "fake8@gmail.com", ShareLog.GROUP),
    Share("fake1", "fake9@gmail.com", ShareLog.GROUP),
    Share("fake1", "fake10@gmail.com", ShareLog.GROUP),
    Share("fake1", "fakea11@gmail.com", ShareLog.GROUP),
    Share("fake1", "fakea12@gmail.com", ShareLog.GROUP),
    Share("fake1", "fakea13@gmail.com", ShareLog.GROUP),
    Share("fake2", "fake1@gmail.com", ShareLog.GROUP),
    Share("fake2", "fake3@gmail.com", ShareLog.GROUP),
    Share("fake2", "fake4@gmail.com", ShareLog.GROUP)
  )
}
