package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.ElasticSearchDAOQuerySupport
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.broadinstitute.dsde.firecloud.model.Document
import org.broadinstitute.dsde.rawls.model.{AttributeName, AttributeString}
import org.parboiled.common.FileUtils
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import spray.json.JsString

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, MINUTES}

class AutoSuggestXSSSpec extends FreeSpec with Matchers with BeforeAndAfterAll with LazyLogging with ElasticSearchDAOQuerySupport {

  final val xssFixtures: Seq[String] = FileUtils.readAllTextFromResource("integrationtest/xssfilterbypass.lst").split("\n")
  final val xssFixturesIndexed:Seq[(String,Int)] =  xssFixtures.zipWithIndex

  val xssDocs: Seq[Document] = xssFixturesIndexed.map { zipped =>
    val (text, id) = zipped
    Document(id.toString, Map(
      AttributeName("library", "datasetName") -> AttributeString(s"marker$id $text ${id}marker")
    ))
  }

  override def beforeAll = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
    // make sure we specify refresh=true here; otherwise, the documents may not be available in the index by the
    // time the tests start, leading to test failures.
    logger.info("indexing fixtures ...")
    searchDAO.bulkIndex(xssDocs, refresh = true)
    logger.info("... fixtures indexed.")
  }

  override def afterAll = {
    searchDAO.deleteIndex()
  }

  "Library integration" - {
    "Elastic Search" - {
      "Index exists" in {
        assert(searchDAO.indexExists())
      }
    }

    "Text search autocomplete suggestions xss safety" - {
      xssFixturesIndexed foreach { zipped =>
        val (text, id) = zipped
        s"suggestions for $id: [[[ $text ]]]" in {
          // we hard-code a limit of 50 characters for the highlighted snippet ... which is smaller
          // than some of the fixture data we're using from https://gist.github.com/rvrsh3ll/09a8b933291f9f98e8ec.
          // in this test, we check both the start and end of the test text.
          // if an attack requires more than 50 chars we'd repel it anyway because of the snippet size.
          assertSuggestionsFor(s"marker$id")
          assertSuggestionsFor(s"${id}marker")
        }
      }
    }
  }

  private def assertSuggestionsFor(searchTerm: String) = {
    val searchResponse = suggestionsFor(searchTerm)
    assert(searchResponse.total > 0, "expected at least one result")

    // remove highlighting from the suggestion so we can match it easier
    val suggestions = (searchResponse.results map {
      case js:JsString => stripHighlight(js.value)
      case _ => fail("suggestion object should be a JsString")
    }).toSet

    suggestions.foreach { sugg =>
      assert(!sugg.contains("<"), "found open-tag!")
      assert(!sugg.contains(">", "found close-tag!"))
    }
  }

  val dur = Duration(2, MINUTES)
  private def suggestionsFor(txt:String) = {
    val criteria = emptyCriteria.copy(searchString = Some(txt))
    Await.result(searchDAO.suggestionsFromAll(criteria, Seq.empty[String]), dur)
  }

  private def stripHighlight(txt:String) = {
    txt.replace(HL_START,"").replace(HL_END,"")
  }

}
