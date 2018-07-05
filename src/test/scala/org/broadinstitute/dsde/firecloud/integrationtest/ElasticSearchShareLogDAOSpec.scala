package org.broadinstitute.dsde.firecloud.integrationtest

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.{searchDAO, shareLogDAO}
import org.broadinstitute.dsde.firecloud.model.ShareLog
import org.broadinstitute.dsde.firecloud.model.ShareLog.Share
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

class ElasticSearchShareLogDAOSpec extends FreeSpec with Matchers with BeforeAndAfterAll with LazyLogging {
  override def beforeAll = {
    // using the delete from search dao, because we don't have recreate in search dao
    // this comment is a copy pasta - todo need to verify this
    searchDAO.recreateIndex()

    // set up example data
    logger.info("indexing fixtures...")
    ElasticSearchShareLogDAOFixtures.fixtureShares foreach { share => shareLogDAO.logShare(share.userId, share.sharee, share.shareType) }

    logger.info("...fixtures indexed.")
  }

  override def afterAll = {
    // using the delete from search dao because we don't have recreate in sharelog dao
    // todo need to confirm this
    searchDAO.deleteIndex()
  }

  "ElasticSearchShareLogDAO" - {
    "logShare" - {
      "should log a share" in {
        val loggedShare = shareLogDAO.logShare("fake2", "fake1@gmail.com", ShareLog.WORKSPACE)
        val check = shareLogDAO.getShares(loggedShare.userId)
        assertResult(loggedShare) {
          check.filter(_.equals(loggedShare))
        }
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
