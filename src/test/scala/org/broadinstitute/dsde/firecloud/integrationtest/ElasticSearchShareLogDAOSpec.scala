package org.broadinstitute.dsde.firecloud.integrationtest

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.{searchDAO, shareLogDAO}
import org.broadinstitute.dsde.firecloud.model.ShareLog
import org.broadinstitute.dsde.firecloud.model.ShareLog.Share
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import scala.util.Try
import scala.util.hashing.MurmurHash3

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
    "logAndGetShares" - {
      "log some shares and get them back" in {
        // set up example data
        val logged = ElasticSearchShareLogDAOFixtures.fixtureShares.sortBy(_.sharee) map { share => shareLogDAO.logShare(share.userId, share.sharee, share.shareType) }
        val check = shareLogDAO.getShares("fake1").sortBy(_.sharee)
        assertResult(logged) { check }
      }
    }
    "logShare" - {
      "should log a share and get it back successfully using the generated MD5 hash" in {
        val share = Share("fake2", "fake1@gmail.com", ShareLog.WORKSPACE, Instant.now)
        val loggedShare = shareLogDAO.logShare(share.userId, share.sharee, share.shareType)
        val id = MurmurHash3.stringHash(share.userId + share.sharee + share.shareType).toString
        val check = shareLogDAO.getShare(id)
        assertResult(loggedShare) { check }
      }
      "should error when attempting to log a duplicate share" in {
        val share = Share("fake2", "fake1@gmail.com", ShareLog.WORKSPACE, Instant.now)
        val error = Try(shareLogDAO.logShare(share.userId, share.sharee, share.shareType))
        assert(error.isFailure, "Should have failed to log duplicate share")
      }
    }
    "getShares" - {
      "should get shares of a specific type and none others" in {
        val expected = shareLogDAO.logShare("fake1", "fake2@gmail.com", ShareLog.GROUP)
        val check = shareLogDAO.getShares("fake1", Some(ShareLog.GROUP))
        assertResult(List(expected)) { check }
      }
    }
  }
}

object ElasticSearchShareLogDAOFixtures {
  val fixtureShares: Seq[Share] = Seq(
    Share("fake1", "fake2@gmail.com", ShareLog.WORKSPACE, Instant.now),
    Share("fake1", "fake3@gmail.com", ShareLog.WORKSPACE, Instant.now),
    Share("fake1", "fake4@gmail.com", ShareLog.WORKSPACE, Instant.now)
  )
}
