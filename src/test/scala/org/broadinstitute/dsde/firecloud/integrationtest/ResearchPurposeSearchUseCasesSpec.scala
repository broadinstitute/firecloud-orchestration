package org.broadinstitute.dsde.firecloud.integrationtest

import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.searchDAO
import org.broadinstitute.dsde.firecloud.model.DataUse.{DiseaseOntologyNodeId, ResearchPurpose}
import org.broadinstitute.dsde.firecloud.model.LibrarySearchResponse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ResearchPurposeSearchUseCasesSpec extends AnyFreeSpec with SearchResultValidation with Matchers with BeforeAndAfterAll with LazyLogging {

  // cases as defined in the doc at
  // https://docs.google.com/a/broadinstitute.org/spreadsheets/d/16XzKpOFCyqRTNy9XHFFPx-Vf4PWFydsWAS7exzx26WM/edit?usp=sharing

  override def beforeAll() = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
    // make sure we specify refresh=true here; otherwise, the documents may not be available in the index by the
    // time the tests start, leading to test failures.
    logger.info("indexing fixtures ...")
    searchDAO.bulkIndex(ResearchPurposeSearchUseCaseFixtures.fixtureDocs, refresh = true)
    logger.info("... fixtures indexed.")
  }

  override def afterAll() = {
    searchDAO.deleteIndex()
  }

  "Library research purpose PO use cases" - {

    "Elastic Search" - {
      "Index exists" in {
        assert(searchDAO.indexExists())
      }
    }

    "Research purpose C: Cancer (DOID:162)" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(DS=Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_162")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set(7, 8, 9, 12, 13, 14, 15, 16, 17, 18, 20, 23, 25, 26, 27, 29, 30, 31),
          searchResponse
        )
      }
    }

    "Research purpose D: Cancer, Methods" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NMDS=true, DS=Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_162")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set(7, 8, 9, 12, 13, 14, 15, 16, 17, 18, 20, 23, 25, 26, 27, 29, 30, 31),
          searchResponse
        )
      }
    }

    "Research purpose E: Controls" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL=true)
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set(7, 8, 13, 14, 15, 16, 17, 18, 20, 23, 25, 26, 27, 29, 30, 31),
          searchResponse
        )
      }
    }

    "Research purpose F: Cancer, controls" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL=true, DS=Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_162")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set(7, 8, 9, 12, 13, 14, 15, 16, 17, 18, 20, 23, 25, 26, 27, 29, 30, 31),
          searchResponse
        )
      }
    }

    "Research purpose G: Diabetes, controls" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL=true, DS=Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_9351")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set(7, 8, 11, 13, 14, 15, 16, 17, 18, 20, 23, 25, 26, 27, 29, 30, 31),
          searchResponse
        )
      }
    }

    "Research purpose H: Commercial use" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NCU=true)
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set(7, 8, 9, 10, 12, 13, 14, 20, 21, 23, 24, 25, 27, 28, 29, 30, 31, 32),
          searchResponse
        )
      }
    }

    "Research purpose I: methods, commercial use" - {
      "should return PO-defined results" in {
        val researchPurpose = ResearchPurpose.default.copy(NCU=true, NMDS=true)
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set(7, 8, 9, 10, 20, 21, 23, 24, 27, 28, 29, 30),
          searchResponse
        )
      }
    }

  }

  private def validateResultNames(expectedNames:Set[Int], response:LibrarySearchResponse): Unit = {
    val stringNames = expectedNames.map(_.toString)
    super.validateResultNames(stringNames, response)
  }



}
