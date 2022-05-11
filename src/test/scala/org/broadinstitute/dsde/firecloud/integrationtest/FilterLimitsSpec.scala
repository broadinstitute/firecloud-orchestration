package org.broadinstitute.dsde.firecloud.integrationtest

import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.searchDAO
import org.broadinstitute.dsde.firecloud.model.SamResource.{AccessPolicyName, ResourceId, UserPolicy}
import org.broadinstitute.dsde.rawls.model.WorkspaceAccessLevels
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.{Duration, MINUTES}

class FilterLimitsSpec extends AnyFreeSpec with Matchers with SearchResultValidation with BeforeAndAfterAll with LazyLogging {

  override def beforeAll() = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
    // make sure we specify refresh=true here; otherwise, the documents may not be available in the index by the
    // time the tests start, leading to test failures.
    logger.info("indexing fixtures ...")
    searchDAO.bulkIndex(IntegrationTestFixtures.fixtureRestrictedDocs, refresh = true)
    logger.info("... fixtures indexed.")
  }

  override def afterAll() = {
    searchDAO.deleteIndex()
  }

  "Library integration" - {
    // TODO: AJ-249 50,000 passes but 75,000 fails
    Seq(2, 5, 100, 1000, 10000, 20000, 50000, 75000, 100000) foreach { limit =>
      s"search with $limit filter criteria" - {
        "returns 1 result without error " in {
          val wsMatchesMap = Map("testing123" -> UserPolicy(ResourceId("testing123"), false, AccessPolicyName(WorkspaceAccessLevels.Read.toString), Seq.empty.toSet, Seq.empty.toSet))
          val wsMap = 0.to(limit).map { num =>
            (num.toString -> UserPolicy(ResourceId(num.toString), false, AccessPolicyName(WorkspaceAccessLevels.Read.toString), Seq.empty.toSet, Seq.empty.toSet))
          }.toMap
          val searchResponse = searchWithFilter(wsMap ++ wsMatchesMap)
          assertResult(wsMatchesMap.size) {
            searchResponse.total
          }
        }
      }
    }
  }

}
