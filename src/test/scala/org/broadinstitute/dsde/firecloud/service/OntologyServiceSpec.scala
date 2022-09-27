package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.{ESResearchPurposeSupport, MockOntologyDAO}
import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurposeRequest
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsValue, JsonParser}
import spray.json.lenses.JsonLenses._


class OntologyServiceSpec extends AnyFreeSpec with Matchers with ScalaFutures {

  val ontologyDao = new MockOntologyDAO()
  val researchPurposeSupport = new ESResearchPurposeSupport(ontologyDao)

  import scala.concurrent.ExecutionContext.Implicits.global

  val ontologyService = new OntologyService(ontologyDao, researchPurposeSupport)

  final implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(300, Seconds)), interval = scaled(Span(2, Seconds)))

  private def jsonFromResearchPurposeRequest(rpRequest: ResearchPurposeRequest): JsValue = {
    val result = ontologyService.buildResearchPurposeQuery(rpRequest).futureValue

    val resultString = result match {
      case RequestComplete(response) => response.toString
      case _ => fail("expected a RequestComplete")
    }

    JsonParser(resultString)
  }

  "/duos/researchPurposeQuery" - {
    "should return empty filter when all parameters are defaulted" in {
      val rpRequest = ResearchPurposeRequest.empty // use all defaults
      val json = jsonFromResearchPurposeRequest(rpRequest)

      json.extract[JsObject](Symbol("bool") / Symbol("must").?) shouldBe empty
      json.extract[JsObject](Symbol("bool") / Symbol("should").?) shouldBe empty
    }

    "should encode DOIDs as integer queries (with prefix)" in {
      val inputDiseaseIDs = Seq(9220, 535) // these need to match the mock data in MockOntologyDAO

      val rpRequest = ResearchPurposeRequest.empty.copy(
        DS = Option(inputDiseaseIDs.map(id => s"http://purl.obolibrary.org/obo/DOID_$id")),
        prefix = Option("abc:")
      )

      val json = jsonFromResearchPurposeRequest(rpRequest)
      val termsPath = Symbol("bool") / Symbol("must") / * / Symbol("bool") / Symbol("should") / * / Symbol("term")

      val diseaseIDs = json.extract[Int](termsPath / "abc:structuredUseRestriction.DS".? / Symbol("value"))
      diseaseIDs should contain allElementsOf inputDiseaseIDs
      // List of diseases should be expanded upward through the disease ontology
      // Ontology can change to being more specific here would make this test brittle
      diseaseIDs.length should be > inputDiseaseIDs.length

      // Specifying specific diseases implies General Research Use (GRU) and Health/Medical/Biomedical (HMB)
      Seq("GRU", "HMB") foreach { code =>
        json.extract[Boolean](termsPath / s"abc:structuredUseRestriction.$code".? / Symbol("value")) should contain theSameElementsAs Seq(true)
      }
    }

  }

}
