package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.DUOS.DuosDataUse
import org.scalatest.{FreeSpec, Matchers}
import spray.json._

class DuosModelSpec extends FreeSpec with Matchers {

  private implicit val impDuosDataUse: ModelJsonProtocol.impDuosDataUse.type = ModelJsonProtocol.impDuosDataUse

  "DUOS DuosDataUse" - {

    "Partially formed valid data use json should parse what's valid" - {
      "generalUse: true, fooBar: 7" in {
        val jsValues: Map[String, JsValue] = Map(
          "generalUse" -> JsBoolean(true),
          "fooBar" -> JsNumber(7)
        )
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.generalUse.getOrElse(false) shouldBe true
      }
    }

    "Incorrectly formed data use json should parse to an empty object" - {
      "fooBar: 7, barBaz: [FOO, BAR]" in {
        val vals = JsArray(JsString("FOO"), JsString("BAR"))
        val jsValues: Map[String, JsValue] = Map(
          "barBaz" -> JsArray(vals),
          "fooBar" -> JsNumber(7)
        )
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.generalUse.isDefined shouldBe false
        duosDataUse.hmbResearch.isDefined shouldBe false
        duosDataUse.diseaseRestrictions.isDefined shouldBe false
        duosDataUse.populationOriginsAncestry.isDefined shouldBe false
        duosDataUse.populationStructure.isDefined shouldBe false
        duosDataUse.commercialUse.isDefined shouldBe false
        duosDataUse.methodsResearch.isDefined shouldBe false
        duosDataUse.aggregateResearch.isDefined shouldBe false
        duosDataUse.controlSetOption.isDefined shouldBe false
        duosDataUse.gender.isDefined shouldBe false
        duosDataUse.pediatric.isDefined shouldBe false
        duosDataUse.populationRestrictions.isDefined shouldBe false
        duosDataUse.dateRestriction.isDefined shouldBe false
        duosDataUse.recontactingDataSubjects.isDefined shouldBe false
        duosDataUse.recontactMay.isDefined shouldBe false
        duosDataUse.recontactMust.isDefined shouldBe false
        duosDataUse.genomicPhenotypicData.isDefined shouldBe false
        duosDataUse.otherRestrictions.isDefined shouldBe false
        duosDataUse.cloudStorage.isDefined shouldBe false
        duosDataUse.ethicsApprovalRequired.isDefined shouldBe false
        duosDataUse.geographicalRestrictions.isDefined shouldBe false
        duosDataUse.other.isDefined shouldBe false
        duosDataUse.illegalBehavior.isDefined shouldBe false
        duosDataUse.addiction.isDefined shouldBe false
        duosDataUse.sexualDiseases.isDefined shouldBe false
        duosDataUse.stigmatizeDiseases.isDefined shouldBe false
        duosDataUse.vulnerablePopulations.isDefined shouldBe false
        duosDataUse.psychologicalTraits.isDefined shouldBe false
        duosDataUse.nonBiomedical.isDefined shouldBe false
      }
    }

    "Correctly formed duos data use json should parse to a DuosDataUse" - {
      "generalUse: true" in {
        val jsValues: Map[String, JsValue] = Map("generalUse" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.generalUse.getOrElse(false) shouldBe true
      }
      "hmbResearch: true" in {
        val jsValues: Map[String, JsValue] = Map("hmbResearch" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.hmbResearch.getOrElse(false) shouldBe true
      }
      "diseaseRestrictions: [DOID_1, DOID_2]" in {
        val diseases = JsArray(JsString("DOID_1"), JsString("DOID_2"))
        val jsValues: Map[String, JsValue] = Map("diseaseRestrictions" -> diseases)
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.diseaseRestrictions.getOrElse(Seq.empty[String]) should not be empty
        duosDataUse.diseaseRestrictions.getOrElse(Seq.empty[String]) should contain ("DOID_1")
        duosDataUse.diseaseRestrictions.getOrElse(Seq.empty[String]) should contain ("DOID_2")
      }
      "populationOriginsAncestry: true" in {
        val jsValues: Map[String, JsValue] = Map("populationOriginsAncestry" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.populationOriginsAncestry.getOrElse(false) shouldBe true
      }
      "populationStructure: true" in {
        val jsValues: Map[String, JsValue] = Map("populationStructure" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.populationStructure.getOrElse(false) shouldBe true
      }
      "commercialUse: true" in {
        val jsValues: Map[String, JsValue] = Map("commercialUse" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.commercialUse.getOrElse(false) shouldBe true
      }
      "methodsResearch: true" in {
        val jsValues: Map[String, JsValue] = Map("methodsResearch" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.methodsResearch.getOrElse(false) shouldBe true
      }
      "aggregateResearch: Yes" in {
        val jsValues: Map[String, JsValue] = Map("aggregateResearch" -> JsString("Yes"))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.aggregateResearch.getOrElse(false) should equal("Yes")
      }
      "controlSetOption: No" in {
        val jsValues: Map[String, JsValue] = Map("controlSetOption" -> JsString("No"))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.controlSetOption.getOrElse(false) should equal("No")
      }
      "gender: F" in {
        val jsValues: Map[String, JsValue] = Map("gender" -> JsString("F"))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.gender.getOrElse(false) should equal("F")
      }
      "pediatric: true" in {
        val jsValues: Map[String, JsValue] = Map("pediatric" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.pediatric.getOrElse(false) shouldBe true
      }
      "populationRestrictions: [POP_1, POP_2]" in {
        val pops = JsArray(JsString("POP_1"), JsString("POP_2"))
        val jsValues: Map[String, JsValue] = Map("populationRestrictions" -> pops)
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.populationRestrictions.getOrElse(Seq.empty[String]) should not be empty
        duosDataUse.populationRestrictions.getOrElse(Seq.empty[String]) should contain ("POP_1")
        duosDataUse.populationRestrictions.getOrElse(Seq.empty[String]) should contain ("POP_2")
      }
      "dateRestriction: 1/1/2018" in {
        val jsValues: Map[String, JsValue] = Map("dateRestriction" -> JsString("1/1/2018"))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.dateRestriction.getOrElse(false) should equal("1/1/2018")
      }
      "recontactingDataSubjects: true" in {
        val jsValues: Map[String, JsValue] = Map("recontactingDataSubjects" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.recontactingDataSubjects.getOrElse(false) shouldBe true
      }
      "recontactMay: No" in {
        val jsValues: Map[String, JsValue] = Map("recontactMay" -> JsString("No"))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.recontactMay.getOrElse(false) should equal("No")
      }
      "recontactMust: Yes" in {
        val jsValues: Map[String, JsValue] = Map("recontactMust" -> JsString("Yes"))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.recontactMust.getOrElse(false) should equal("Yes")
      }
      "genomicPhenotypicData: Yes" in {
        val jsValues: Map[String, JsValue] = Map("genomicPhenotypicData" -> JsString("Yes"))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.genomicPhenotypicData.getOrElse(false) should equal("Yes")
      }
      "otherRestrictions: true" in {
        val jsValues: Map[String, JsValue] = Map("otherRestrictions" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.otherRestrictions.getOrElse(false) shouldBe true
      }
      "cloudStorage: No" in {
        val jsValues: Map[String, JsValue] = Map("cloudStorage" -> JsString("No"))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.cloudStorage.getOrElse(false) should equal("No")
      }
      "ethicsApprovalRequired: true" in {
        val jsValues: Map[String, JsValue] = Map("ethicsApprovalRequired" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.ethicsApprovalRequired.getOrElse(false) shouldBe true
      }
      "geographicalRestrictions: US" in {
        val jsValues: Map[String, JsValue] = Map("geographicalRestrictions" -> JsString("US"))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.geographicalRestrictions.getOrElse(false) should equal("US")
      }
      "other: Other" in {
        val jsValues: Map[String, JsValue] = Map("other" -> JsString("Other"))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.other.getOrElse(false) should equal("Other")
      }
      "illegalBehavior: true" in {
        val jsValues: Map[String, JsValue] = Map("illegalBehavior" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.illegalBehavior.getOrElse(false) shouldBe true
      }
      "addiction: true" in {
        val jsValues: Map[String, JsValue] = Map("addiction" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.addiction.getOrElse(false) shouldBe true
      }
      "sexualDiseases: true" in {
        val jsValues: Map[String, JsValue] = Map("sexualDiseases" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.sexualDiseases.getOrElse(false) shouldBe true
      }
      "stigmatizeDiseases: true" in {
        val jsValues: Map[String, JsValue] = Map("stigmatizeDiseases" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.stigmatizeDiseases.getOrElse(false) shouldBe true
      }
      "vulnerablePopulations: true" in {
        val jsValues: Map[String, JsValue] = Map("vulnerablePopulations" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.vulnerablePopulations.getOrElse(false) shouldBe true
      }
      "psychologicalTraits: true" in {
        val jsValues: Map[String, JsValue] = Map("psychologicalTraits" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.psychologicalTraits.getOrElse(false) shouldBe true
      }
      "nonBiomedical: true" in {
        val jsValues: Map[String, JsValue] = Map("nonBiomedical" -> JsBoolean(true))
        val duosDataUse: DuosDataUse = DuosDataUse.apply(jsValues)
        duosDataUse.nonBiomedical.getOrElse(false) shouldBe true
      }
    }
  }

}
