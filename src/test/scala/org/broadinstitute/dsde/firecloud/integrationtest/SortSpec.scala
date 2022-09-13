package org.broadinstitute.dsde.firecloud.integrationtest

import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.broadinstitute.dsde.firecloud.model.{LibrarySearchParams, LibrarySearchResponse}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import spray.json.DefaultJsonProtocol._
import spray.json.{JsNumber, JsObject, JsString, JsValue}

import scala.concurrent.Await
import scala.concurrent.duration._

class SortSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll with LazyLogging {

  val dur = Duration(2, MINUTES)


  override def beforeAll() = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
    // make sure we specify refresh=true here; otherwise, the documents may not be available in the index by the
    // time the tests start, leading to test failures.
    logger.info("indexing fixtures ...")
    searchDAO.bulkIndex(IntegrationTestFixtures.fixtureDocs, refresh = true)
    logger.info("... fixtures indexed.")
  }

  override def afterAll() = {
    searchDAO.deleteIndex()
  }

  "Library integration" - {
    "Elastic Search" - {
      "Index exists" in {
        assert(searchDAO.indexExists())
      }
    }
    "search with no sort (or filter) criteria" - {
      "returns all results in engine-defined order" in {
        val searchResponse = sortBy(None, None)
        assertResult(IntegrationTestFixtures.datasetTuples.size) {searchResponse.total}
        // results are sorted by relevancy/native index order, which we won't test here
      }
    }
    "search with empty string as sort criteria" - {
      "returns all results in engine-defined order" in {
        val searchResponse = sortBy(Some(""), None)
        assertResult(IntegrationTestFixtures.datasetTuples.size) {searchResponse.total}
        // results are sorted by relevancy/native index order, which we won't test here
      }
    }
    "sort by datasetName asc" - {
      "finds the correct first result" in {
        val searchResponse = sortBy("library:datasetName", "asc")
        assertResult(IntegrationTestFixtures.datasetTuples.size) {searchResponse.total}
        validateFirstResult("library:datasetName", "TCGA_ACC_ControlledAccess", searchResponse)
      }
    }
    "sort by datasetName desc" - {
      "finds the correct first result" in {
        val searchResponse = sortBy("library:datasetName", "desc")
        assertResult(IntegrationTestFixtures.datasetTuples.size) {searchResponse.total}
        validateFirstResult("library:datasetName", "ZZZ <encodingtest>&foo", searchResponse)
      }
    }
    "sort by datasetName with no sort order" - {
      "implicitly applies asc sort order" in {
        val searchResponse = sortBy("library:datasetName")
        assertResult(IntegrationTestFixtures.datasetTuples.size) {searchResponse.total}
        validateFirstResult("library:datasetName", "TCGA_ACC_ControlledAccess", searchResponse)
      }
    }
    "sort by datasetName with pagination" - {
      "properly sorts and pages" in {
        val criteria = emptyCriteria.copy(sortField = Some("library:datasetName"), from = 2)
        val searchResponse = searchFor(criteria)
        assertResult(IntegrationTestFixtures.datasetTuples.size) {searchResponse.total}
        validateFirstResult("library:datasetName", "TCGA_BRCA_OpenAccess", searchResponse)
      }
    }
    "sort by numSubjects asc" - {
      "finds the correct first result" in {
        val searchResponse = sortBy("library:numSubjects", "asc")
        assertResult(IntegrationTestFixtures.datasetTuples.size) {searchResponse.total}
        validateFirstResult("library:numSubjects", 1, searchResponse)
      }
    }
    "sort by numSubjects desc" - {
      "finds the correct first result" in {
        val searchResponse = sortBy("library:numSubjects", "desc")
        assertResult(IntegrationTestFixtures.datasetTuples.size) {searchResponse.total}
        validateFirstResult("library:numSubjects", 4455667, searchResponse)
      }
    }
    "sort by numSubjects desc with no sort order" - {
      "implicitly applies asc sort order" in {
        val searchResponse = sortBy("library:numSubjects")
        assertResult(IntegrationTestFixtures.datasetTuples.size) {searchResponse.total}
        validateFirstResult("library:numSubjects", 1, searchResponse)
      }
    }
    "sort by numSubjects with pagination" - {
      "properly sorts and pages" in {
        val criteria = emptyCriteria.copy(sortField = Some("library:numSubjects"), sortDirection = Some("desc"), from = 3, size = 2)
        val searchResponse = searchFor(criteria)
        assertResult(IntegrationTestFixtures.datasetTuples.size) {searchResponse.total}
        validateFirstResult("library:numSubjects", 444, searchResponse)
      }
    }

  }


  private def sortBy(sortField: String): LibrarySearchResponse = {
    sortBy(Some(sortField), None)
  }
  private def sortBy(sortField: String, sortDirection: String): LibrarySearchResponse = {
    sortBy(Some(sortField), Some(sortDirection))
  }
  private def sortBy(sortField: Option[String] = None, sortDirection: Option[String] = None): LibrarySearchResponse = {
    val criteria = emptyCriteria.copy(sortField=sortField, sortDirection=sortDirection)
    searchFor(criteria)
  }

  private def searchFor(criteria: LibrarySearchParams) = {
    Await.result(searchDAO.findDocuments(criteria, Seq.empty[String], Map.empty), dur)
  }

  private def validateFirstResult(field: String, expectedValue: String, response: LibrarySearchResponse): Unit = {
    validateFirstResult(field, JsString(expectedValue), response)
  }
  private def validateFirstResult(field: String, expectedValue: Int, response: LibrarySearchResponse): Unit = {
    validateFirstResult(field, JsNumber(expectedValue), response)
  }
  private def validateFirstResult(field:String, expectedValue: JsValue, response:LibrarySearchResponse): Unit = {
    val res = getFirstResult(response)
    val actualValue = res.fields.getOrElse(field, fail(s"field $field does not exist in results") )
    assertResult(expectedValue) {actualValue}
  }

  private def getFirstResult(response:LibrarySearchResponse): JsObject = {
    response.results.head.asJsObject
  }

}
