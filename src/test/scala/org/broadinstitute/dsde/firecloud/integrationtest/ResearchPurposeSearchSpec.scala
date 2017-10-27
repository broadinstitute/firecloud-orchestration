package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.searchDAO
import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

class ResearchPurposeSearchSpec extends FreeSpec with SearchResultValidation with Matchers with BeforeAndAfterAll with LazyLogging {

  override def beforeAll = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
    // make sure we specify refresh=true here; otherwise, the documents may not be available in the index by the
    // time the tests start, leading to test failures.
    logger.info("indexing fixtures ...")
    searchDAO.bulkIndex(ResearchPurposeSearchTestFixtures.fixtureDocs, refresh = true)
    logger.info("... fixtures indexed.")
  }

  override def afterAll = {
    searchDAO.deleteIndex()
  }

  "Library research-purpose-aware search" - {
    "Elastic Search" - {
      "Index exists" in {
        assert(searchDAO.indexExists())
      }
    }
    "Research purpose for aggregate analysis (NAGR)" - {
      "should return any dataset where NAGR is false and is (GRU or HMB)" in {
        val researchPurpose = ResearchPurpose.default.copy(NAGR = true)
        val searchResponse = searchWithPurpose(researchPurpose)
        assertResult(2) {searchResponse.total}
        validateResultNames(
          Set("CSA_9220", "L_1240"),
          searchResponse
        )
      }
    }
    "Research purpose for population origins/ancestry (POA)" - {
      "should return any dataset where GRU is true" in {
        val researchPurpose = ResearchPurpose.default.copy(POA = true)
        val searchResponse = searchWithPurpose(researchPurpose)
        assertResult(1) {searchResponse.total}
        validateResultNames(
          Set("CSA_9220"),
          searchResponse
        )
      }
    }
    "Research purpose for commercial use (NCU)" - {
      "should return any dataset where NPU and NCU are both false" in {
        val researchPurpose = ResearchPurpose.default.copy(NCU = true)
        val searchResponse = searchWithPurpose(researchPurpose)
        assertResult(3) {searchResponse.total}
        validateResultNames(
          Set("CSA_9220", "L_1240", "FASD_0050696"),
          searchResponse
        )
      }
    }
    // TODO: autosuggest tests
    // TODO: RP + filter tests
    // TODO: RP + search tests
    // TODO: RPs that specify multiple selections
  }

}
