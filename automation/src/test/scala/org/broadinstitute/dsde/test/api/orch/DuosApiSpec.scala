package org.broadinstitute.dsde.test.api.orch

import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.UserPool
import org.broadinstitute.dsde.workbench.service.Orchestration
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsonParser}
import spray.json.lenses.JsonLenses._

class DuosApiSpec extends AnyFreeSpec with Matchers {

  "/duos/researchPurposeQuery" - {
    "should return empty filter when all parameters are defaulted" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val result = Orchestration.duos.researchPurposeQuery()
      val json = JsonParser(result)

      json.extract[JsObject](Symbol("bool") / Symbol("must").?) shouldBe empty
      json.extract[JsObject](Symbol("bool") / Symbol("should").?) shouldBe empty
    }

    "should encode DOIDs as integer queries (with prefix)" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()

      val inputDiseaseIDs = Seq(9351, 1287)
      val result = Orchestration.duos.researchPurposeQuery(
        DS = inputDiseaseIDs.map(id => s"http://purl.obolibrary.org/obo/DOID_$id"),
        prefix = "abc:")

      val json = JsonParser(result)
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
