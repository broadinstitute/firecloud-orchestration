package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.dataaccess.MockOntologyDAO
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
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
    docs.map { doc => logger.info(s"Document: ${doc.toString}") }
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
        // TODO: returned list should equal the number of datasets indexed
        val searchResponse = searchFor("project")
        logger.info(s"Searching on: 'project'")
        searchResponse shouldNot be(null)
        logger.info(s"Result size: ${searchResponse.results.size}")
      }

//      "should not find non-existent dataset" in {
//        // TODO
//      }
//
//      "should not find GRU:true datasets" in {
//        // TODO
//      }
//
//      "should not find HMB:true datasets" in {
//        // TODO
//      }
//
//      "should not find NCU:true datasets" in {
//        // TODO
//      }
//
//      "should not find NPU:true datasets" in {
//        // TODO
//      }
//
//      "should not find NCU:true datasets" in {
//        // TODO
//      }
//
//      "should not find NDMS:true datasets" in {
//        // TODO
//      }
//
//      "should not find NAGR:true datasets" in {
//        // TODO
//      }
//
//      "should not find NCTRL:true datasets" in {
//        // TODO
//      }
//
//      "should not find RS-PD:true datasets" in {
//        // TODO
//      }
//
//      "should not find RS-G:true datasets" in {
//        // TODO
//      }
//
//      "should not find RS-FM:true datasets" in {
//        // TODO
//      }
//
//      "should not find RS-M:true datasets" in {
//        // TODO
//      }
//
//      "should not find DS:non-empty list datasets" in {
//        // TODO
//      }
//
//      "should not find RS-POP:non-empty list datasets" in {
//        // TODO
//      }

    }

  }

}
