package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.dataaccess.MockOntologyDAO
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.broadinstitute.dsde.firecloud.model.LibrarySearchResponse
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionTestFixtures.DataUseRestriction
import org.broadinstitute.dsde.firecloud.service.{DataUseRestrictionTestFixtures, LibraryServiceSupport}
import org.broadinstitute.dsde.rawls.model.Workspace
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

class DataUseRestrictionSearchSpec extends FreeSpec with SearchResultValidation with BeforeAndAfterAll with Matchers with LibraryServiceSupport {

  val datasets: Seq[Workspace] = DataUseRestrictionTestFixtures.allDatasets

  override def beforeAll: Unit = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
    // make sure we specify refresh=true here; otherwise, the documents may not be available in the index by the
    // time the tests start, leading to test failures.
    logger.info("indexing fixtures ...")
    val docs = Await.result(indexableDocuments(datasets, new MockOntologyDAO), dur)
    searchDAO.bulkIndex(docs, refresh = true)
    logger.info("... fixtures indexed.")
  }

  override def afterAll: Unit = {
    searchDAO.deleteIndex()
  }

  "Library Data Use Restriction Indexing" - {

    "Elastic Search" - {
      "Index exists" in {
        assert(searchDAO.indexExists())
      }
    }

    "Data Use Restriction Search" -{

      "should find all datasets" in {
        // All dataset workspaces have the same name for ease of testing
        val searchResponse = searchFor("projectName")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(datasets.size)
      }

      "should not find non-existent dataset" in {
        val searchResponse = searchFor("nonexistentdataset")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(0)
      }

      "GRU dataset should be indexed as true" in {
        val searchResponse = searchFor("GRU")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).exists { dur => dur.GRU }
      }

      "HMB dataset should be indexed as true" in {
        val searchResponse = searchFor("HMB")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).exists { dur => dur.HMB }
      }

      "NCU dataset should be indexed as true" in {
        val searchResponse = searchFor("NCU")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).exists { dur => dur.NCU }
      }

      "NPU dataset should be indexed as true" in {
        val searchResponse = searchFor("NPU")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).exists { dur => dur.NPU }
      }

      "NDMS dataset should be indexed as true" in {
        val searchResponse = searchFor("NDMS")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).exists { dur => dur.NDMS }
      }

      "NAGR dataset should be indexed as true" in {
        val searchResponse = searchFor("NAGR")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(3)
        getDataUseRestrictions(searchResponse).exists { dur => dur.NAGR }
      }

      "NCTRL dataset should be indexed as true" in {
        val searchResponse = searchFor("NCTRL")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).exists { dur => dur.NCTRL }
      }

      "RS-PD dataset should be indexed as true" in {
        val searchResponse = searchFor("RS-PD")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).exists { dur => dur.`RS-PD` }
      }

      "RS-G dataset should be indexed as true" in {
        val searchResponse = searchFor("RS-G")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(3)
        getDataUseRestrictions(searchResponse).exists { dur => dur.`RS-G` }
      }

      "RS-FM dataset should be indexed as true" in {
        val searchResponse = searchFor("RS-FM")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).exists { dur => dur.`RS-FM` }
      }

      "RS-M dataset should be indexed as true" in {
        val searchResponse = searchFor("RS-M")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).exists { dur => dur.`RS-M` }
      }

      "DS:non-empty list dataset should be have values" in {
        val searchResponse = searchFor("DS")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).head.DS.nonEmpty should be(true)
      }

      "RS-POP:non-empty list dataset should be have values" in {
        val searchResponse = searchFor("RS-POP")
        searchResponse shouldNot be(null)
        searchResponse.results.size should be(1)
        getDataUseRestrictions(searchResponse).head.`RS-POP`.nonEmpty should be(true)
      }

    }

  }


  //////////////////
  // Utility methods
  //////////////////


  override def searchFor(text: String): LibrarySearchResponse = {
    val criteria = emptyCriteria.copy(
      searchString = Some(text),
      size = datasets.size)
    Await.result(searchDAO.findDocuments(criteria, Seq.empty[String]), dur)
  }

  private def getDataUseRestrictions(searchResponse: LibrarySearchResponse): Seq[DataUseRestriction] = {
    searchResponse.results.map { hit =>
      val sdur = hit.asJsObject.fields("library:structuredUseRestriction").asJsObject
      sdur.convertTo[DataUseRestriction]
    }
  }

}
