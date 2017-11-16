package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.searchDAO
import org.broadinstitute.dsde.firecloud.model.DataUse.{DiseaseOntologyNodeId, ResearchPurpose}
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

class ResearchPurposeSearchUseCasesSpec extends FreeSpec with SearchResultValidation with Matchers with BeforeAndAfterAll with LazyLogging {

  // cases as defined in the doc at
  // https://docs.google.com/a/broadinstitute.org/spreadsheets/d/16XzKpOFCyqRTNy9XHFFPx-Vf4PWFydsWAS7exzx26WM/edit?usp=sharing

  override def beforeAll = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
    // make sure we specify refresh=true here; otherwise, the documents may not be available in the index by the
    // time the tests start, leading to test failures.
    logger.info("indexing fixtures ...")
    searchDAO.bulkIndex(ResearchPurposeSearchUseCaseFixtures.fixtureDocs, refresh = true)
    logger.info("... fixtures indexed.")
  }

  override def afterAll = {
    searchDAO.deleteIndex()
  }

  "Library research purpose PO use cases" - {

    "Elastic Search" - {
      "Index exists" in {
        assert(searchDAO.indexExists())
      }
    }

    "Research purpose B: Cancer (DOID:162)" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(DS=Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_162")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("7", "8", "9", "12", "13", "14", "15", "16"),
          searchResponse
        )
      }
    }

    "Research purpose C: Cancer, Methods" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NMDS=true, DS=Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_162")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("7", "8", "9", "12", "15", "16"),
          searchResponse
        )
      }
    }

    "Research purpose D: Controls" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL=true)
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("7", "8", "13", "14", "15", "16"),
          searchResponse
        )
      }
    }

    "Research purpose E: Cancer, controls" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL=true, DS=Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_162")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("7", "8", "9", "12", "13", "14", "15", "16"),
          searchResponse
        )
      }
    }

    "Research purpose F: Diabetes, controls" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL=true, DS=Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_9351")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("7", "8", "11", "13", "14", "15", "16"),
          searchResponse
        )
      }
    }

    "Research purpose G: Commercial use" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NCU=true)
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("7", "8", "9", "10", "12", "13", "14"),
          searchResponse
        )
      }
    }

  }
}
