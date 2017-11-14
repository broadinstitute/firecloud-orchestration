package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.dataaccess.MockOntologyDAO
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.broadinstitute.dsde.firecloud.model.LibrarySearchResponse
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionTestFixtures.DataUseRestriction
import org.broadinstitute.dsde.firecloud.service.{DataUseRestrictionTestFixtures, LibraryServiceSupport}
import org.broadinstitute.dsde.rawls.model.{AttributeName, Workspace}
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import spray.json.DefaultJsonProtocol._

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
        val searchResponse = searchFor("GRU-unique")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(GRU = true), Seq("GRU"))
      }

      "HMB dataset should be indexed as true" in {
        val searchResponse = searchFor("HMB-unique")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(HMB = true), Seq("HMB"))
      }

      "NCU dataset should be indexed as true" in {
        val searchResponse = searchFor("NCU-unique")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(NCU = true), Seq("NCU"))
      }

      "NPU dataset should be indexed as true" in {
        val searchResponse = searchFor("NPU-unique")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(NPU = true), Seq("NPU"))
      }

      "NDMS dataset should be indexed as true" in {
        val searchResponse = searchFor("NDMS-unique")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(NDMS = true), Seq("NDMS"))
      }

      "NAGR:Yes should be indexed as true" in {
        val searchResponse = searchFor("NAGRYes")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(NAGR = true), Seq("NAGR"))
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
        val searchResponse = searchFor("NCTRL-unique")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(NCTRL = true), Seq("NCTRL"))
      }

      "RS-PD dataset should be indexed as true" in {
        val searchResponse = searchFor("RSPD-unique")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-PD` = true), Seq("RS-PD"))
      }

      "RS-G:Female should be indexed as RS-G:true, RS-FM:true" in {
        val searchResponse = searchFor("RSGFemale")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-G` = true, `RS-FM` = true), Seq("RS-G", "RS-FM"))
      }

      "RS-G:Male should be indexed as RS-G:true, RS-M:true" in {
        val searchResponse = searchFor("RSGMale")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-G` = true, `RS-M` = true), Seq("RS-G", "RS-M"))
      }

      "RS-G:N/A should be indexed as RS-G:false" in {
        val searchResponse = searchFor("RSGNA")
        assertDataUseRestrictions(searchResponse, DataUseRestriction())
      }

      "RS-FM dataset should be indexed as true" in {
        val searchResponse = searchFor("RSGFemale")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-G` = true, `RS-FM` = true), Seq("RS-G", "RS-FM"))
      }

      "RS-M dataset should be indexed as true" in {
        val searchResponse = searchFor("RSGMale")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-G` = true, `RS-M` = true), Seq("RS-G", "RS-M"))
      }

      "DS:non-empty list dataset should have values" in {
        val searchResponse = searchFor("DS-unique")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(DS = DataUseRestrictionTestFixtures.diseaseValuesInts), DataUseRestrictionTestFixtures.diseaseValuesLabels.map("DS:" + _))
      }

      "RS-POP:non-empty list dataset should have values" in {
        val searchResponse = searchFor("RS-POP")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(`RS-POP` = Seq("TERM-1", "TERM-2")))
      }

      "IRB dataset should be indexed as true" in {
        val searchResponse = searchFor("IRB-unique")
        assertDataUseRestrictions(searchResponse, DataUseRestriction(IRB = true), Seq("IRB"))
      }

      "'EVERYTHING' dataset should have a mix of values" in {
        val searchResponse = searchFor("EVERYTHING")
        assertDataUseRestrictions(searchResponse,
          DataUseRestriction(
            GRU = true,
            HMB = true,
            DS = DataUseRestrictionTestFixtures.diseaseValuesInts,
            NCU = true,
            NPU = true,
            NDMS = true,
            NAGR = true,
            NCTRL = true,
            `RS-PD` = true,
            `RS-G` = true,
            `RS-FM` = true,
            `RS-POP` = Seq("TERM-1", "TERM-2"),
            IRB = true
          ),
          Seq("NPU", "RS-G", "NCU", "HMB", "NDMS", "RS-FM", "NCTRL", "RS-PD", "NAGR", "GRU", "IRB") ++
            DataUseRestrictionTestFixtures.diseaseValuesLabels.map("DS:" + _)
        )
      }

      "'TOP_THREE' dataset should have a mix of values" in {
        val searchResponse = searchFor("TOP_THREE")
        assertDataUseRestrictions(searchResponse,
          DataUseRestriction(
            GRU = true,
            HMB = true,
            DS = DataUseRestrictionTestFixtures.diseaseValuesInts
          ),
          Seq("GRU", "HMB") ++
            DataUseRestrictionTestFixtures.diseaseValuesLabels.map("DS:" + _)
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
      val sdur = hit.asJsObject.fields(AttributeName.toDelimitedName(structuredUseRestrictionAttributeName)).asJsObject
      sdur.convertTo[DataUseRestriction]
    }
  }

  private def getDataUseDisplayCodes(searchResponse: LibrarySearchResponse): Seq[String] = {
    searchResponse.results.flatMap { hit =>
      val jsObj = hit.asJsObject
      if (jsObj.getFields(AttributeName.toDelimitedName(dataUseDisplayAttributeName)).nonEmpty) {
        jsObj.fields(AttributeName.toDelimitedName(dataUseDisplayAttributeName)).convertTo[Seq[String]]
      } else { Seq.empty}
    }
  }

  private def assertDataUseRestrictions(searchResponse: LibrarySearchResponse, expected: DataUseRestriction, expectedCodes: Seq[String] = Seq.empty[String]): Unit = {
    searchResponse shouldNot be(null)

    if (searchResponse.results.size != 1) {
      logger.error(s"Size: ${searchResponse.results.size}")
      searchResponse.results.map { sr => logger.error(s"${sr.toString}")}
    }

    searchResponse.results.size should be(1)
    val listActual = getDataUseRestrictions(searchResponse)
    listActual.foreach { actual =>
      assertResult(expected) {
        actual
      }
    }

    val ddulCodes: Seq[String] = getDataUseDisplayCodes(searchResponse)
    expectedCodes should contain theSameElementsAs ddulCodes

  }

}
