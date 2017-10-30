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

  // we don't have to test any use cases of the user omitting a research purpose - all the
  // other tests do that.
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
        validateResultNames(
          Set("one", "two", "six", "seven", "eleven", "twelve"),
          searchResponse
        )
      }
      "should intersect with a standard facet filter" in {
        val researchPurpose = ResearchPurpose.default.copy(NAGR = true)
        val filter = Map("library:projectName" -> Seq("helium"))
        val searchResponse = searchWithPurpose(researchPurpose, filter)
        validateResultNames(
          Set("six", "seven"),
          searchResponse
        )
      }
      "should intersect with a text search" in {
        val researchPurpose = ResearchPurpose.default.copy(NAGR = true)
        val searchResponse = searchWithPurpose(researchPurpose, "lazy")
        validateResultNames(
          Set("eleven", "twelve"),
          searchResponse
        )
      }
      "should affect search suggestions" in {
        val researchPurpose = ResearchPurpose.default.copy(NAGR = true)
        val searchResponse = suggestWithPurpose(researchPurpose, "anti")
        validateSuggestions(
          Set("antiaging", "antialias", "antibody", "antic", "anticoagulant", "anticorruption"),
          searchResponse
        )
      }
    }

    "Research purpose for population origins/ancestry (POA)" - {
      "should return any dataset where GRU is true" in {
        val researchPurpose = ResearchPurpose.default.copy(POA = true)
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("one", "six", "eleven"),
          searchResponse
        )
      }
      "should intersect with a standard facet filter" in {
        val researchPurpose = ResearchPurpose.default.copy(POA = true)
        val filter = Map("library:projectName" -> Seq("helium"))
        val searchResponse = searchWithPurpose(researchPurpose, filter)
        validateResultNames(
          Set("six"),
          searchResponse
        )
      }
      "should intersect with a text search" in {
        val researchPurpose = ResearchPurpose.default.copy(POA = true)
        val searchResponse = searchWithPurpose(researchPurpose, "lazy")
        validateResultNames(
          Set("eleven"),
          searchResponse
        )
      }
      "should affect search suggestions" in {
        val researchPurpose = ResearchPurpose.default.copy(POA = true)
        val searchResponse = suggestWithPurpose(researchPurpose, "anti")
        validateSuggestions(
          Set("antiaging", "antibody", "anticoagulant"),
          searchResponse
        )
      }
    }

    "Research purpose for commercial use (NCU)" - {
      "should return any dataset where NPU and NCU are both false" in {
        val researchPurpose = ResearchPurpose.default.copy(NCU = true)
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("two", "four", "seven", "nine", "twelve", "fourteen"),
          searchResponse
        )
      }
      "should intersect with a standard facet filter" in {
        val researchPurpose = ResearchPurpose.default.copy(NCU = true)
        val filter = Map("library:projectName" -> Seq("helium"))
        val searchResponse = searchWithPurpose(researchPurpose, filter)
        validateResultNames(
          Set("seven", "nine"),
          searchResponse
        )
      }
      "should intersect with a text search" in {
        val researchPurpose = ResearchPurpose.default.copy(NCU = true)
        val searchResponse = searchWithPurpose(researchPurpose, "lazy")
        validateResultNames(
          Set("twelve", "fourteen"),
          searchResponse
        )
      }
      "should affect search suggestions" in {
        val researchPurpose = ResearchPurpose.default.copy(NCU = true)
        val searchResponse = suggestWithPurpose(researchPurpose, "anti")
        validateSuggestions(
          Set("antialias", "antibacterial", "antic", "anticipate", "anticorruption", "antidisestablishmentarianism"),
          searchResponse
        )
      }
    }

    "Research purpose with multiple restrictions enabled" - {
      "should intersect each restriction" in {
        val researchPurpose = ResearchPurpose.default.copy(NAGR = true, NCU = true)
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("two", "seven", "twelve"),
          searchResponse
        )
      }
    }

  }
}
