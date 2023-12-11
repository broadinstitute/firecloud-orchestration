package org.broadinstitute.dsde.firecloud.integrationtest

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.ElasticSearchDAOQuerySupport
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import spray.json.{JsObject, JsString}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, MINUTES}

class AutoSuggestSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll with LazyLogging with ElasticSearchDAOQuerySupport {

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

    "Text search autocomplete suggestions correctness" - {

      val testCases: Seq[(String, Seq[String])] = Seq(
        ("brca open",    Seq("~~~intentional fail for verifying test behavior~~~")),
        ("brca o",       Seq("TCGA_BRCA_OpenAccess")),
        ("BRCA_Cont",    Seq("TCGA_BRCA_ControlledAccess")),
        ("glio",         Seq("Glioblastoma multiforme")),
        ("thy",          Seq("Thyroid carcinoma", "Thymoma", "TCGA_THYM_ControlledAccess")),
        ("test",         Seq("testing123","test indication")),
        ("kidn",         Seq("Kidney Chromophobe","Kidney Renal Clear Cell Carcinoma","Kidney Renal Papillary Cell Carcinoma")),
        ("Mesothelioma", Seq("Mesothelioma")),
        ("encodingtest", Seq("ZZZ <encodingtest>&foo")), // make sure the result is not encoded
        ("xyz",          Seq.empty[String]),
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
            case jso:JsObject =>
              val flds = jso.fields
              assert(flds.contains("suggestion"), "suggestion result should have a `suggestion` key")
              val sugg = flds("suggestion")
              sugg match {
                case js:JsString =>
                  val suggStr = js.value
                  // if ES returns a highlight, ensure the highlight is valid for the source
                  val hlt = flds.get("highlight")
                  hlt match {
                    case Some(js:JsString) =>
                      assert(suggStr.contains(js.value), "if highlight exists, suggestion should contain highlight")
                    case None => // nothing to validate here
                    case _ => fail("if highlight key exists, should be a JsString")
                  }
                  suggStr
                case _ => fail("suggestion key should be a JsString")
              }
            case _ => fail("suggestion object should be a JsObject")
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

  "Per-field autocomplete suggestions for populating the catalog wizard" - {
    "should not return duplicates" in {
      // "Pancreatic adenocarcinoma" exists twice, but should be de-duplicated
      val results = Await.result(searchDAO.suggestionsForFieldPopulate("library:datasetOwner", "pan"), dur)
      assertResult(Seq("Pancreatic adenocarcinoma")) { results }
    }
    "should return multiple distinct suggestions" in {
      val results = Await.result(searchDAO.suggestionsForFieldPopulate("library:datasetOwner", "thy"), dur)
      assertResult(Set("Thyroid carcinoma", "Thymoma")) { results.toSet }
    }
  }

  val dur = Duration(2, MINUTES)
  private def suggestionsFor(txt:String) = {
    val criteria = emptyCriteria.copy(searchString = Some(txt))
    Await.result(searchDAO.suggestionsFromAll(criteria, Seq.empty[String], Map.empty), dur)
  }

}
