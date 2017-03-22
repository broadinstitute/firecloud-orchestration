package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import spray.json.JsString

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, MINUTES}

class AutoSuggestSpec extends FreeSpec with Matchers with BeforeAndAfterAll with LazyLogging {

  override def beforeAll = {
    // use re-create here, since instantiating the DAO will create it in the first place
    searchDAO.recreateIndex()
    // make sure we specify refresh=true here; otherwise, the documents may not be available in the index by the
    // time the tests start, leading to test failures.
    logger.info("indexing fixtures ...")
    searchDAO.bulkIndex(IntegrationTestFixtures.fixtureDocs, refresh = true)
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

    "Text search autocomplete suggestions correctness" - {

      val testCases: Seq[(String, Seq[String])] = Seq(
        ("brca open",    Seq("TCGA_BRCA_OpenAccess")),
        ("BRCA_Cont",    Seq("TCGA_BRCA_ControlledAccess")),
        ("glio",         Seq("Glioblastoma multiforme")),
        ("thy",          Seq("Thyroid carcinoma", "Thymoma", "TCGA_THYM_ControlledAccess")),
        ("test",         Seq("testing123","test indication")),
        ("kidn",         Seq("Kidney Chromophobe","Kidney Renal Clear Cell Carcinoma","Kidney Renal Papillary Cell Carcinoma")),
        ("Mesothelioma", Seq("Mesothelioma")),
        ("xyz",          Seq.empty[String]),
        ("tc",           Seq.empty[String]), // we have a min ngram of 3
        ("idney",        Seq.empty[String]), // we only do leading-edge ngrams
        ("access",       Seq.empty[String])  // we only do leading-edge ngrams
      )

      testCases foreach { x:(String, Seq[String]) =>
        val (crit, expected) = x

        s"suggestions for '$crit'" in {
          val searchResponse = suggestionsFor(crit)
          assertResult(expected.size) {
            searchResponse.total
          }
          // remove highlighting from the suggestion so we can match it easier
          val suggestions = (searchResponse.results map {
            case js:JsString => stripHighlight(js.value)
            case _ => fail("suggestion object should be a JsString")
          }).toSet
          // mangle the expected to lowercase and Set-ify it
          val ex = expected.map(_.toLowerCase).toSet

          assertResult(ex) {
            suggestions
          }
        }
      }
    }
  }

  val dur = Duration(2, MINUTES)
  private def suggestionsFor(txt:String) = {
    val criteria = emptyCriteria.copy(searchString = Some(txt))
    Await.result(searchDAO.suggestionsFromAll(criteria, Seq.empty[String]), dur)
  }

  final val HL_START = "<strong class='es-highlight'>"
  final val HL_END = "</strong>"
  private def stripHighlight(txt:String) = {
    txt.replace(HL_START,"").replace(HL_END,"")
  }

}
