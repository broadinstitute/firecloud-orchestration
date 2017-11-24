package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.searchDAO
import org.broadinstitute.dsde.firecloud.model.DataUse.{DiseaseOntologyNodeId, ResearchPurpose}
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

    "Disease focused research (DS)" - {
      "should return any dataset where the disease matches exactly" in {
        val researchPurpose = ResearchPurpose.default.copy(DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_535")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("one", "two", "six", "seven", "eleven", "twelve", "sixteen", "eighteen"), // GRU, HMB, and sleep disorder
          searchResponse
        )
      }
      "should return any dataset where the RP's disease is a child of the dataset's disease" in {
        val researchPurpose = ResearchPurpose.default.copy(DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_9220")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("one", "two", "six", "seven", "eleven", "twelve", "sixteen", "seventeen", "eighteen", "twenty"), // GRU, HMB, central sleep apnea, and sleep disorder
          searchResponse
        )
      }
      "should return any GRU or HMB dataset" in {
        // disease search for leukemia, which is not in our test fixtures
        val researchPurpose = ResearchPurpose.default.copy(DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_1240")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("one", "two", "six", "seven", "eleven", "twelve"),
          searchResponse
        )
      }
      "should intersect with a standard facet filter" in {
        val researchPurpose = ResearchPurpose.default.copy(DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_535")))
        val filter = Map("library:projectName" -> Seq("beryllium"))
        val searchResponse = searchWithPurpose(researchPurpose, filter)
        validateResultNames(
          Set("sixteen", "eighteen"),
          searchResponse
        )
      }
      "should intersect with a text search" in {
        val researchPurpose = ResearchPurpose.default.copy(DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_535")))
        val searchResponse = searchWithPurpose(researchPurpose, "lazy")
        validateResultNames(
          Set("eleven", "twelve"),
          searchResponse
        )
      }
      "should affect search suggestions" in {
        val researchPurpose = ResearchPurpose.default.copy(DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_535")))
        val searchResponse = suggestWithPurpose(researchPurpose, "anti")
        validateSuggestions(
          // Set("one", "two", "six", "seven", "eleven", "twelve", "sixteen", "eighteen"), // GRU, HMB, and sleep disorder
          Set("antiaging", "antialias", "antibody", "antic", "anticoagulant", "anticorruption", "antiegalitarian", "antielitism"),
          searchResponse
        )
      }
    }

    "Methods development/Validation study (NMDS)" - {
      "should return any dataset where NMDS is true and the disease matches exactly" in {
        val researchPurpose = ResearchPurpose.default.copy(NMDS=true, DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_535")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("one", "two", "six", "seven", "eleven", "twelve", "sixteen", "eighteen"), // NMDS=false or (NMDS=true and disease-match logic)
          searchResponse
        )
        // NB: this doesn't match fixture "twenty" because even though the NMDS clauses are satisfied, the DS
        // clause is not. In other words, if you made this search without specifying NMDS=true, you wouldn't
        // match on "twenty".
      }
      "should return any dataset where NMDS is true and the RP's disease is a child of the dataset's disease" in {
        val researchPurpose = ResearchPurpose.default.copy(NMDS=true, DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_9220")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("one", "two", "six", "seven", "eleven", "twelve", "sixteen", "seventeen", "eighteen", "twenty"),
          searchResponse
        )
      }
      "should return any dataset where NMDS is false" in {
        val researchPurpose = ResearchPurpose.default.copy(NMDS = true)
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("one", "six", "eleven", "sixteen", "twenty"),
          searchResponse
        )
      }
      "should intersect with a standard facet filter" in {
        val researchPurpose = ResearchPurpose.default.copy(NMDS=true, DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_535")))
        val filter = Map("library:projectName" -> Seq("beryllium"))
        val searchResponse = searchWithPurpose(researchPurpose, filter)
        validateResultNames(
          Set("sixteen", "eighteen"),
          searchResponse
        )
      }
      "should intersect with a text search" in {
        val researchPurpose = ResearchPurpose.default.copy(NMDS=true, DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_9220")))
        val searchResponse = searchWithPurpose(researchPurpose, "lazy")
        validateResultNames(
          Set("eleven", "twelve"),
          searchResponse
        )
      }
      "should affect search suggestions" in {
        val researchPurpose = ResearchPurpose.default.copy(NMDS=true, DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_9220")))
        val searchResponse = suggestWithPurpose(researchPurpose, "anti")
        validateSuggestions(
          Set("antiaging", "antialias", "antibody", "antic", "anticoagulant", "anticorruption", "antiegalitarian", "antielectron", "antielitism", "antifashion"),
          searchResponse
        )
      }
    }

    "Control set (NCTRL)" - {
      "should return any dataset where the disease matches exactly" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL = true, DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_535")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("two", "six", "twelve", "sixteen", "eighteen"),
          searchResponse
        )
      }
      "should return any dataset where the RP's disease is a child of the dataset's disease" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL = true, DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_9220")))
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("two", "six", "twelve", "sixteen", "seventeen", "eighteen", "twenty"),
          searchResponse
        )
      }
      "should return any dataset where NCTRL is false and is (GRU or HMB)" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL = true)
        val searchResponse = searchWithPurpose(researchPurpose)
        validateResultNames(
          Set("two", "six", "twelve"),
          searchResponse
        )
      }
      "should intersect with a standard facet filter" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL = true, DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_535")))
        val filter = Map("library:projectName" -> Seq("beryllium"))
        val searchResponse = searchWithPurpose(researchPurpose, filter)
        validateResultNames(
          Set("sixteen", "eighteen"),
          searchResponse
        )
      }
      "should intersect with a text search" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL = true, DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_535")))
        val searchResponse = searchWithPurpose(researchPurpose, "lazy")
        validateResultNames(
          Set("twelve"),
          searchResponse
        )
      }
      "should affect search suggestions" in {
        val researchPurpose = ResearchPurpose.default.copy(NCTRL = true, DS = Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_535")))
        val searchResponse = suggestWithPurpose(researchPurpose, "anti")
        validateSuggestions(
          Set("antialias", "antibody", "anticorruption", "antiegalitarian", "antielitism"),
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
