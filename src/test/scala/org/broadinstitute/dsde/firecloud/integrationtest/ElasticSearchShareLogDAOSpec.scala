package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.{searchDAO, shareLogDAO}
import org.broadinstitute.dsde.firecloud.model.ShareLog.{Share, ShareType}
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

import scala.util.Try

class ElasticSearchShareLogDAOSpec extends FreeSpec with Matchers with BeforeAndAfterAll with LazyLogging {
  override def beforeAll = {
    // using the recreate from search dao because we don't have recreate in sharelog dao
    searchDAO.recreateIndex()
    ElasticSearchShareLogDAOSpecFixtures.fixtureShares map { share =>
      shareLogDAO.logShare(share.userId, share.sharee, share.shareType)
    }
  }

  override def afterAll = {
    // using the delete from search dao because we don't have recreate in sharelog dao
    searchDAO.deleteIndex()
  }

  "ElasticSearchShareLogDAO" - {
    "logShare" - {
      "should log a share and get it back successfully using the generated MD5 hash" in {
        val share = Share("roger", "syd@gmail.com", ShareType.WORKSPACE)
        val loggedShare = shareLogDAO.logShare(share.userId, share.sharee, share.shareType)
        val check = shareLogDAO.getShare(share)
        assertResult(loggedShare) { check }
      }
      "should successfully log a duplicate share" in {
        val loggedShare = shareLogDAO.logShare("fake4", "fake3@gmail.com", ShareType.WORKSPACE)
        val check = Try(shareLogDAO.logShare(loggedShare.userId, loggedShare.sharee, loggedShare.shareType))
        assert(check.isSuccess)
      }
    }
    "logShares" - {
      "should log multiple shares for a single user and share type" in {
        val userId = "fake2"
        val shareType = ShareType.GROUP
        val expected = ElasticSearchShareLogDAOSpecFixtures.fixtureShares.filter(_.userId == userId).filter(_.shareType == shareType)
        val sharees = expected.map(_.sharee)
        val check = shareLogDAO.logShares(userId, sharees, ShareType.GROUP)
        assertResult(expected.map(s => (s.userId, s.sharee, s.shareType))) {
          check.map(s => (s.userId, s.sharee, s.shareType))
        }
      }
    }
    "getShares" - {
      "should get shares of all types for a user" in {
        val expected = ElasticSearchShareLogDAOSpecFixtures.fixtureShares
          .filter(s => s.userId.equals("fake1"))
          .sortBy(s => (s.sharee, s.shareType))
        val check = shareLogDAO.getShares("fake1").sortBy(s => (s.sharee, s.shareType))

        assertResult(expected.size) { check.size }
        assertResult(expected.map(s => (s.userId, s.sharee, s.shareType))) { check.map(s => (s.userId, s.sharee, s.shareType)) }
      }
      "should get shares of a specific type and none others" in {
        val expected = ElasticSearchShareLogDAOSpecFixtures.fixtureShares
          .filter(s => s.userId.equals("fake1"))
          .filter(s => s.shareType.equals(ShareType.GROUP))
          .sortBy(s => (s.sharee, s.shareType))
        val check = shareLogDAO.getShares("fake1", Some(ShareType.GROUP)).sortBy(s => (s.sharee, s.shareType))

        assertResult(expected.size) { check.size }
        assertResult(expected.map(s => (s.userId, s.sharee, s.shareType))) { check.map(s => (s.userId, s.sharee, s.shareType)) }
      }
    }
  }
}

object ElasticSearchShareLogDAOSpecFixtures {
  val fixtureShares: Seq[Share] = Seq(
    Share("fake1", "fake2@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fake3@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fake4@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fake5@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fake6@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fake7@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fake8@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fake9@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fake10@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fakea1@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fakea2@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fakea3@gmail.com", ShareType.WORKSPACE),
    Share("fake2", "fake1@gmail.com", ShareType.WORKSPACE),
    Share("fake2", "fake3@gmail.com", ShareType.WORKSPACE),
    Share("fake2", "fake4@gmail.com", ShareType.WORKSPACE),
    Share("fake1", "fake2@gmail.com", ShareType.GROUP),
    Share("fake1", "fake3@gmail.com", ShareType.GROUP),
    Share("fake1", "fake4@gmail.com", ShareType.GROUP),
    Share("fake1", "fake5@gmail.com", ShareType.GROUP),
    Share("fake1", "fake6@gmail.com", ShareType.GROUP),
    Share("fake1", "fake7@gmail.com", ShareType.GROUP),
    Share("fake1", "fake8@gmail.com", ShareType.GROUP),
    Share("fake1", "fake9@gmail.com", ShareType.GROUP),
    Share("fake1", "fake10@gmail.com", ShareType.GROUP),
    Share("fake1", "fakea11@gmail.com", ShareType.GROUP),
    Share("fake1", "fakea12@gmail.com", ShareType.GROUP),
    Share("fake1", "fakea13@gmail.com", ShareType.GROUP),
    Share("fake2", "fake1@gmail.com", ShareType.GROUP),
    Share("fake2", "fake3@gmail.com", ShareType.GROUP),
    Share("fake2", "fake4@gmail.com", ShareType.GROUP)
  )
}
