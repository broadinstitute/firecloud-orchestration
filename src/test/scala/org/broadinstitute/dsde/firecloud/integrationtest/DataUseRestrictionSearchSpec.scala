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

    "Data Use Restriction Search" - {

      "should find all datasets" in {
        // All dataset workspaces have the same "library:projectName" value for ease of testing
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
        assertDataUseRestrictions(searchResponse, DataUseRestriction(GRU = true))
      }

      "HMB dataset should be indexed as true" in {
        val searchResponse = searchFor("HMB")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(HMB = true))
      }

      "NCU dataset should be indexed as true" in {
        val searchResponse = searchFor("NCU")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(NCU = true))
      }

      "NPU dataset should be indexed as true" in {
        val searchResponse = searchFor("NPU")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(NPU = true))
      }

      "NDMS dataset should be indexed as true" in {
        val searchResponse = searchFor("NDMS")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(NDMS = true))
      }

      "NAGR:Yes should be indexed as true" in {
        val searchResponse = searchFor("NAGRYes")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(NAGR = true))
      }

      "NAGR:No should be indexed as false" in {
        val searchResponse = searchFor("NAGRNo")
        assertDataUseRestrictions(searchResponse, DataUseRestriction())
      }

      "NAGR:Unspecified should be indexed as false" in {
        val searchResponse = searchFor("NAGRUnspecified")
        assertDataUseRestrictions(searchResponse, DataUseRestriction())
      }

      "NCTRL dataset should be indexed as true" in {
        val searchResponse = searchFor("NCTRL")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(NCTRL = true))
      }

      "RS-PD dataset should be indexed as true" in {
        val searchResponse = searchFor("RS-PD")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-PD` = true))
      }

      "RS-G:Female should be indexed as RS-G:true, RS-FM:true" in {
        val searchResponse = searchFor("RSGFemale")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-G` = true, `RS-FM` = true))
      }

      "RS-G:Male should be indexed as RS-G:true, RS-M:true" in {
        val searchResponse = searchFor("RSGMale")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-G` = true, `RS-M` = true))
      }

      "RS-G:N/A should be indexed as RS-G:false" in {
        val searchResponse = searchFor("RSGNA")
        assertDataUseRestrictions(searchResponse, DataUseRestriction())
      }

      "RS-FM dataset should be indexed as true" in {
        val searchResponse = searchFor("RS-FM")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-G` = true, `RS-FM` = true))
      }

      "RS-M dataset should be indexed as true" in {
        val searchResponse = searchFor("RS-M")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-G` = true, `RS-M` = true))
      }

      "DS:non-empty list dataset should have values" in {
        val searchResponse = searchFor("DS")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(DS = Seq("TERM-1", "TERM-2")))
      }

      "RS-POP:non-empty list dataset should have values" in {
        val searchResponse = searchFor("RS-POP")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-POP` = Seq("TERM-1", "TERM-2")))
      }

      "'EVERYTHING' dataset should have a mix of values" in {
        val searchResponse = searchFor("EVERYTHING")
        assertDataUseRestrictions(searchResponse,
          DataUseRestriction(
            GRU = true,
            HMB = true,
            DS = Seq("TERM-1", "TERM-2"),
            NCU = true,
            NPU = true,
            NDMS = true,
            NAGR = true,
            NCTRL = true,
            `RS-PD` = true,
            `RS-G` = true,
            `RS-FM` = true,
            `RS-POP` = Seq("TERM-1", "TERM-2")
          )
        )
      }

      "'TOP_THREE' dataset should have a mix of values" in {
        val searchResponse = searchFor("TOP_THREE")
        assertDataUseRestrictions(searchResponse,
          DataUseRestriction(
            GRU = true,
            HMB = true,
            DS = Seq("TERM-1", "TERM-2")
          )
        )
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

  private def assertDataUseRestrictions(searchResponse: LibrarySearchResponse, expected: DataUseRestriction): Unit = {
    searchResponse shouldNot be(null)
    searchResponse.results.size should be(1)
    val listActual = getDataUseRestrictions(searchResponse)
    listActual.foreach { actual =>
      assertResult(expected) {
        actual
      }
    }

  }

}
