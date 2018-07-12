package org.broadinstitute.dsde.firecloud.integrationtest

import java.time.Instant

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
  }

  override def afterAll = {
    // using the delete from search dao because we don't have recreate in sharelog dao
    // todo need to confirm this
    searchDAO.deleteIndex()
  }

  "ElasticSearchShareLogDAO" - {
    // todo need to fix the getShares query
    "logAndGetShares" ignore {
      "log some shares and get them back" in {
        // set up example data
        val logged = ElasticSearchShareLogDAOSpecFixtures.fixtureShares.sortBy(_.sharee) map { share =>
          shareLogDAO.logShare(share.userId, share.sharee, share.shareType)
        }
        val check = shareLogDAO.getShares("fake1").sortBy(_.sharee)
        assertResult(logged) { check }
      }
    }
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
    // todo need to fix the getShares query
    "getShares" ignore {
      "should get shares of a specific type and none others" in {
        val expected = shareLogDAO.logShare("fake1", "fake2@gmail.com", ShareLog.GROUP)
        val check = shareLogDAO.getShares("fake1", Some(ShareLog.GROUP))
        assertResult(List(expected)) { check }
      }
    }
  }
}

object ElasticSearchShareLogDAOSpecFixtures {
  val fixtureShares: Seq[Share] = Seq(
    Share("fake1", "fake2@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake3@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake4@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake2@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake3@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake4@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake2@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake3@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake4@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake2@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake3@gmail.com", ShareLog.WORKSPACE),
    Share("fake1", "fake4@gmail.com", ShareLog.WORKSPACE)
  )
}
