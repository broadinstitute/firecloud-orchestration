package org.broadinstitute.dsde.test.api.orch

import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.UserPool
import org.broadinstitute.dsde.workbench.service.Orchestration
import org.scalatest.{FreeSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json.lenses.JsonLenses._
import spray.json.{JsObject, JsonParser}

class DuosApiSpec extends FreeSpec with Matchers {

  "/duos/researchPurposeQuery" - {
    "should return empty filter when all parameters are defaulted" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val result = Orchestration.duos.researchPurposeQuery()
      val json = JsonParser(result)

      json.extract[JsObject]('bool / 'must.?) shouldBe empty
      json.extract[JsObject]('bool / 'should.?) shouldBe empty
    }

    "should encode DOIDs as integer queries (with prefix)" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()

      val inputDiseaseIDs = Seq(9351, 1287)
      val result = Orchestration.duos.researchPurposeQuery(
        DS = inputDiseaseIDs.map(id => s"http://purl.obolibrary.org/obo/DOID_$id"),
        prefix = "abc:")

      val json = JsonParser(result)
      val termsPath = 'bool / 'must / * / 'bool / 'should / * / 'term

      val diseaseIDs = json.extract[Int](termsPath / "abc:structuredUseRestriction.DS".? / 'value)
      diseaseIDs should contain allElementsOf inputDiseaseIDs
      // List of diseases should be expanded upward through the disease ontology
      // Ontology can change to being more specific here would make this test brittle
      diseaseIDs.length should be > inputDiseaseIDs.length

      // Specifying specific diseases implies General Research Use (GRU) and Health/Medical/Biomedical (HMB)
      Seq("GRU", "HMB") foreach { code =>
        json.extract[Boolean](termsPath / s"abc:structuredUseRestriction.$code".? / 'value) should contain theSameElementsAs Seq(true)
      }
    }
  }
}
