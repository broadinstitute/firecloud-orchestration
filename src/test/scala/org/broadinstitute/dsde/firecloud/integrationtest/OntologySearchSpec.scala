package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

class OntologySearchSpec extends FreeSpec with Matchers with BeforeAndAfterAll with LazyLogging with SearchResultValidation {

  override def beforeAll = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
    // make sure we specify refresh=true here; otherwise, the documents may not be available in the index by the
    // time the tests start, leading to test failures.
    logger.info("indexing fixtures ...")
    searchDAO.bulkIndex(OntologySearchTestFixtures.fixtureDocs, refresh = true)
    logger.info("... fixtures indexed.")
  }

  override def afterAll = {
    searchDAO.deleteIndex()
  }

  /*
    OntologySearchTextFixtures has five datasets, with these ontology nodes:

    CSA_9220  : central sleep apnea < sleep apnea < sleep disorder < disease of mental health < disease
    E_4325    : Ebola hemorrhagic fever < viral infectious disease < disease by infectious agent < disease
    L_1240    : leukemia < hematologic cancer < immune system cancer < organ system cancer < cancer < disease of cellular proliferation < disease
    HC_2531   : hematologic cancer < immune system cancer < organ system cancer < cancer < disease of cellular proliferation < disease
    D_4       : Disease
    None      : no doid
   */

  "Library integration ontology-aware search" - {
    "Elastic Search" - {
      "Index exists" in {
        assert(searchDAO.indexExists())
      }
    }
    "search for 'disease'" - {
      "should find all datasets with an ontology node" in {
        val searchResponse = searchFor("disease")
        assertResult(5) {searchResponse.total}
        assert(searchResponse.results.forall(js =>
          js.asJsObject.fields.contains("library:diseaseOntologyID")))
      }
    }
    "search for 'disease of mental health'" - {
      "should find a dataset tagged to central sleep apnea, but not leukemia" in {
        // leukemia has a parent of "disease of cellular proliferation". We won't match
        // that text because 1) "of" is a stop word, and 2) we need to match 3<75% tokens
        val searchResponse = searchFor("disease of mental health")
        assertResult(1) {searchResponse.total}
        validateResultNames(
          Set("CSA_9220"),
          searchResponse
        )
      }
    }
    "search for 'ebola fever'" - {
      "should find a dataset tagged directly to ebola" in {
        val searchResponse = searchFor("ebola fever")
        assertResult(1) {searchResponse.total}
        validateResultNames(
          Set("E_4325"),
          searchResponse
        )
      }
    }
    "search for 'hematologic cancer'" - {
      "should find datasets to hematologic cancer or its children" in {
        val searchResponse = searchFor("hematologic cancer")
        assertResult(2) {searchResponse.total}
        validateResultNames(
          Set("L_1240", "HC_2531"),
          searchResponse
        )
      }
    }
    "search for 'leukemia'" - {
      "should find datasets to leukemia but not its parents" in {
        val searchResponse = searchFor("leukemia")
        assertResult(1) {searchResponse.total}
        validateResultNames(
          Set("L_1240"),
          searchResponse
        )
      }
    }
    "searches that include parents" - {
      "should match minimum of 3<75% terms" in {
        val searchResponse = searchFor("disease cellular proliferation single origin coffee")
        assertResult(0) {searchResponse.total}
      }
      "should not span multiple parent nodes" in {
        val searchResponse = searchFor("hematologic immune organ proliferation")
        assertResult(0) {searchResponse.total}
      }
      "should not span leaf and parents" in {
        val searchResponse = searchFor("ebola virus disease")
        assertResult(0) {searchResponse.total}
      }
    }
  }

}
