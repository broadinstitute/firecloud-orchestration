package org.broadinstitute.dsde.firecloud.service

import java.lang.reflect.Field

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.MockOntologyDAO
import org.broadinstitute.dsde.firecloud.model.DUOS.DuosDataUse
import org.broadinstitute.dsde.firecloud.model.DataUse.StructuredDataRequest
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionTestFixtures._
import org.broadinstitute.dsde.rawls.model._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.language.postfixOps

class DataUseRestrictionSupportSpec extends AnyFreeSpec with Matchers with DataUseRestrictionSupport {

  "DataUseRestrictionSupport" - {

    "Structured Use Restriction" - {

      "when questionnaire answers are used to populate restriction fields" - {

        "and all consent codes are true or filled in" in {
          val ontologyDAO = new MockOntologyDAO
          val request = StructuredDataRequest(generalResearchUse = true,
            healthMedicalBiomedicalUseRequired = true,
            diseaseUseRequired = Array("http://purl.obolibrary.org/obo/DOID_4325","http://purl.obolibrary.org/obo/DOID_2531"),
            commercialUseProhibited = true,
            forProfitUseProhibited = true,
            methodsResearchProhibited = true,
            aggregateLevelDataProhibited = true,
            controlsUseProhibited = true,
            genderUseRequired = "female",
            pediatricResearchRequired = true,
            irbRequired = true,
            prefix = Some("blah"))

          val expected = Map("blahconsentCodes" -> Array("NAGR","NMDS","NCTRL","RS-G","GRU","RS-PD","NCU","RS-FM","NPU","HMB","IRB","DS:Ebola hemorrhagic fever","DS:hematologic cancer").toJson,
            "blahdulvn" -> FireCloudConfig.Duos.dulvn.toJson,
            "blahstructuredUseRestriction" -> Map(
              "NPU" -> true.toJson,
              "RS-PD" -> true.toJson,
              "NCU" -> true.toJson,
              "RS-G" -> true.toJson,
              "IRB" -> true.toJson,
              "NAGR" -> true.toJson,
              "RS-FM" -> true.toJson,
              "RS-M" -> false.toJson,
              "NMDS"-> true.toJson,
              "NCTRL" -> true.toJson,
              "GRU" ->true.toJson,
              "HMB" -> true.toJson,
              "DS" -> Array(4325,2531).toJson).toJson)

          val result = generateStructuredUseRestrictionAttribute(request, ontologyDAO)
          result should be (expected)
        }

        "and all consent codes are false or empty" in {
          val ontologyDAO = new MockOntologyDAO
          val request = StructuredDataRequest(generalResearchUse = false,
            healthMedicalBiomedicalUseRequired = false,
            diseaseUseRequired = Array(),
            commercialUseProhibited = false,
            forProfitUseProhibited = false,
            methodsResearchProhibited = false,
            aggregateLevelDataProhibited = false,
            controlsUseProhibited = false,
            genderUseRequired = "",
            pediatricResearchRequired = false,
            irbRequired = false,
            prefix = None)

          val expected = Map("consentCodes" -> Array.empty[String].toJson,
            "dulvn" -> FireCloudConfig.Duos.dulvn.toJson,
            "structuredUseRestriction" -> Map(
              "NPU" -> false.toJson,
              "RS-PD" -> false.toJson,
              "NCU" -> false.toJson,
              "RS-G" -> false.toJson,
              "IRB" -> false.toJson,
              "NAGR" -> false.toJson,
              "RS-FM" -> false.toJson,
              "RS-M" -> false.toJson,
              "NMDS"-> false.toJson,
              "NCTRL" -> false.toJson,
              "GRU" -> false.toJson,
              "HMB" -> false.toJson,
              "DS" -> Array.empty[String].toJson).toJson)

          val result = generateStructuredUseRestrictionAttribute(request, ontologyDAO)
          result should be (expected)
        }

        "and consent codes are a mixture of true and false" in {
          val ontologyDAO = new MockOntologyDAO
          val request = StructuredDataRequest(generalResearchUse = false,
            healthMedicalBiomedicalUseRequired = true,
            diseaseUseRequired = Array("http://purl.obolibrary.org/obo/DOID_1240"),
            commercialUseProhibited = false,
            forProfitUseProhibited = true,
            methodsResearchProhibited = false,
            aggregateLevelDataProhibited = false,
            controlsUseProhibited = true,
            genderUseRequired = "Male",
            pediatricResearchRequired = false,
            irbRequired = true,
            prefix = Some("library"))

          val expected = Map("libraryconsentCodes" -> Array("NCTRL","RS-G","RS-M","NPU","HMB","IRB","DS:leukemia").toJson,
            "librarydulvn" -> FireCloudConfig.Duos.dulvn.toJson,
            "librarystructuredUseRestriction" -> Map(
              "NPU" -> true.toJson,
              "RS-PD" -> false.toJson,
              "NCU" -> false.toJson,
              "RS-G" -> true.toJson,
              "IRB" -> true.toJson,
              "NAGR" -> false.toJson,
              "RS-FM" -> false.toJson,
              "RS-M" -> true.toJson,
              "NMDS"-> false.toJson,
              "NCTRL" -> true.toJson,
              "GRU" -> false.toJson,
              "HMB" -> true.toJson,
              "DS" -> Array(1240).toJson).toJson)

          val result = generateStructuredUseRestrictionAttribute(request, ontologyDAO)
          result should be (expected)
        }
      }

      "when there are library data use restriction fields" - {

        "dataset should have a fully populated data use restriction attribute" in {
          allDatasets.map { ds =>
            val ontologyDAO = new MockOntologyDAO
            val attrs: Map[AttributeName, Attribute] = generateStructuredAndDisplayAttributes(ds, ontologyDAO).structured
            val durAtt: Attribute = attrs.getOrElse(structuredUseRestrictionAttributeName, AttributeNull)
            durAtt shouldNot be(AttributeNull)
            val dur = makeDurFromWorkspace(ds, ontologyDAO)
            dur shouldNot be(null)
          }
        }

        "dur should have appropriate gender codes populated" in {
          genderDatasets.map { ds =>
            val ontologyDAO = new MockOntologyDAO
            val dur: DataUseRestriction = makeDurFromWorkspace(ds, ontologyDAO)
            if (ds.name.equalsIgnoreCase("Female")) {
              dur.`RS-G` should be(true)
              dur.`RS-FM` should be(true)
              dur.`RS-M` should be(false)
            } else if (ds.name.equalsIgnoreCase("Male")) {
              dur.`RS-G` should be(true)
              dur.`RS-FM` should be(false)
              dur.`RS-M` should be(true)
            } else {
              dur.`RS-G` should be(false)
              dur.`RS-FM` should be(false)
              dur.`RS-M` should be(false)
            }
          }
        }

        "dur should have appropriate NAGR code populated" in {
          nagrDatasets.map { ds =>
            val ontologyDAO = new MockOntologyDAO
            val dur: DataUseRestriction = makeDurFromWorkspace(ds, ontologyDAO)
            if (ds.name.equalsIgnoreCase("Yes")) {
              dur.NAGR should be(true)
            } else {
              dur.NAGR should be(false)
            }
          }
        }

        "dataset should have a true value for the consent code for which it was specified" in {
          val durs: Map[String, DataUseRestriction] = booleanDatasets.flatMap { ds =>
            val ontologyDAO = new MockOntologyDAO
            Map(ds.name -> makeDurFromWorkspace(ds, ontologyDAO))
          }.toMap

          booleanCodes.map { code =>
            val dur: DataUseRestriction = durs(code)
            checkBooleanTrue(dur, code) should be(true)
          }
        }

        "dataset should have the correct disease values for the consent code for which it was specified" in {
          val durs: Map[String, DataUseRestriction] = diseaseDatasets.flatMap { ds =>
            val ontologyDAO = new MockOntologyDAO
            Map(ds.name -> makeDurFromWorkspace(ds, ontologyDAO))
          }.toMap

          Seq("DS").foreach { code =>
            val dur: DataUseRestriction = durs(code)
            checkDiseaseValues(dur, code)
          }
        }

      }

      "when there are no library data use restriction fields" - {

        "dataset should not have any data use restriction for empty attributes" in {
          val workspace: WorkspaceDetails = mkWorkspace(Map.empty[AttributeName, Attribute], "empty", "empty")
          val ontologyDAO = new MockOntologyDAO
          val attrs: Map[AttributeName, Attribute] = generateStructuredAndDisplayAttributes(workspace, ontologyDAO).structured
          attrs should be(empty)
        }

        "dataset should not have any data use restriction for non-library attributes" in {
          val ontologyDAO = new MockOntologyDAO
          val nonLibraryAttributes = Map(
            AttributeName.withDefaultNS("name") -> AttributeString("one"),
            AttributeName.withDefaultNS("namespace") -> AttributeString("two"),
            AttributeName.withDefaultNS("workspaceId") -> AttributeString("three"),
            AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString("one"), AttributeString("two"), AttributeString("three")))
          )
          val workspace: WorkspaceDetails = mkWorkspace(nonLibraryAttributes, "non-library", "non-library")
          val attrs: Map[AttributeName, Attribute] = generateStructuredAndDisplayAttributes(workspace, ontologyDAO).structured
          attrs should be(empty)
        }

      }

    }

    "Display Use Restriction" - {

      "when there are library data use restriction fields" - {

        "valid datasets should have some form of data use display attribute" in {
          val ontologyDAO = new MockOntologyDAO
          validDisplayDatasets.map { ds =>
            val attrs: Map[AttributeName, Attribute] = generateStructuredAndDisplayAttributes(ds, ontologyDAO).display
            val codes: Seq[String] = getValuesFromAttributeValueListAsAttribute(attrs.get(consentCodesAttributeName))
            codes shouldNot be(empty)
          }
        }

        "datasets with single boolean code should have that single display code" in {
          val ontologyDAO = new MockOntologyDAO
          booleanDatasets.map { ds =>
            val attrs: Map[AttributeName, Attribute] = generateStructuredAndDisplayAttributes(ds, ontologyDAO).display
            val codes: Seq[String] = getValuesFromAttributeValueListAsAttribute(attrs.get(consentCodesAttributeName))
            // Boolean datasets are named with the same code value
            codes should contain theSameElementsAs Seq(ds.name)
          }
        }

        "'EVERYTHING' dataset should have the right codes" in {
          val ontologyDAO = new MockOntologyDAO
          val attrs = generateStructuredAndDisplayAttributes(everythingDataset.head, ontologyDAO).display
          val codes: Seq[String] = getValuesFromAttributeValueListAsAttribute(attrs.get(consentCodesAttributeName))
          val expected = booleanCodes ++ Seq("RS-G", "RS-FM", "NAGR") ++ diseaseValuesLabels.map(s => s"DS:$s")
          codes should contain theSameElementsAs expected
        }

        "'TOP_THREE' dataset should have the right codes" in {
          val ontologyDAO = new MockOntologyDAO
          val attrs: Map[AttributeName, Attribute] = generateStructuredAndDisplayAttributes(topThreeDataset.head, ontologyDAO).display
          val codes: Seq[String] = getValuesFromAttributeValueListAsAttribute(attrs.get(consentCodesAttributeName))
          val expected = Seq("GRU", "HMB") ++ diseaseValuesLabels.map(s => s"DS:$s")
          codes should contain theSameElementsAs expected
        }
      }

      "when there are missing/invalid library data use restriction terms" - {

        "dataset should not have any data use display codes for empty attributes" in {
          val ontologyDAO = new MockOntologyDAO
          val workspace: WorkspaceDetails = mkWorkspace(Map.empty[AttributeName, Attribute], "empty", "empty")
          val attrs: Map[AttributeName, Attribute] = generateStructuredAndDisplayAttributes(workspace, ontologyDAO).display
          attrs should be(empty)
        }

        "dataset should not have any data use restriction for non-library attributes" in {
          val ontologyDAO = new MockOntologyDAO
          val nonLibraryAttributes: Map[AttributeName, Attribute] = Map(
            AttributeName.withDefaultNS("name") -> AttributeString("one"),
            AttributeName.withDefaultNS("namespace") -> AttributeString("two"),
            AttributeName.withDefaultNS("workspaceId") -> AttributeString("three"),
            AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString("one"), AttributeString("two"), AttributeString("three")))
          )
          val workspace: WorkspaceDetails = mkWorkspace(nonLibraryAttributes, "non-library", "non-library")
          val attrs: Map[AttributeName, Attribute] = generateStructuredAndDisplayAttributes(workspace, ontologyDAO).display
          attrs should be(empty)
        }

      }

    }

    "ORSP-based restrictions" - {
      val ontologyDAO = new MockOntologyDAO

      "when translating DUOS restrictions to FireCloud restrictions" - {
        "should return no attributes if DUOS is empty" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse, ontologyDAO)
          assert(attrs.isEmpty)
        }
        "should translate boolean TRUEs" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            generalUse = Some(true),
            hmbResearch = Some(true),
            commercialUse = Some(true),
            pediatric = Some(true),
            methodsResearch = Some(true)
          ), ontologyDAO)
          val expected = Map(
            AttributeName.withLibraryNS("GRU") -> AttributeBoolean(true),
            AttributeName.withLibraryNS("HMB") -> AttributeBoolean(true),
            AttributeName.withLibraryNS("NCU") -> AttributeBoolean(true),
            AttributeName.withLibraryNS("RS-PD") -> AttributeBoolean(true),
            AttributeName.withLibraryNS("NMDS") -> AttributeBoolean(true)
          )
          assertResult(expected) {attrs}
        }
        "should translate boolean FALSEs" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            generalUse = Some(false),
            hmbResearch = Some(false),
            commercialUse = Some(false),
            pediatric = Some(false),
            methodsResearch = Some(false)
          ), ontologyDAO)
          val expected = Map(
            AttributeName.withLibraryNS("GRU") -> AttributeBoolean(false),
            AttributeName.withLibraryNS("HMB") -> AttributeBoolean(false),
            AttributeName.withLibraryNS("NCU") -> AttributeBoolean(false),
            AttributeName.withLibraryNS("RS-PD") -> AttributeBoolean(false),
            AttributeName.withLibraryNS("NMDS") -> AttributeBoolean(false)
          )
          assertResult(expected) {attrs}
        }
        "should translate mixed boolean TRUE/FALSEs" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            generalUse = Some(true),
            hmbResearch = Some(false),
            commercialUse = Some(true),
            pediatric = Some(false),
            methodsResearch = Some(true)
          ), ontologyDAO)
          val expected = Map(
            AttributeName.withLibraryNS("GRU") -> AttributeBoolean(true),
            AttributeName.withLibraryNS("HMB") -> AttributeBoolean(false),
            AttributeName.withLibraryNS("NCU") -> AttributeBoolean(true),
            AttributeName.withLibraryNS("RS-PD") -> AttributeBoolean(false),
            AttributeName.withLibraryNS("NMDS") -> AttributeBoolean(true)
          )
          assertResult(expected) {attrs}
        }
        "should handle 'yes' string values from DUOS as boolean TRUEs" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            aggregateResearch = Some("Yes"),
            controlSetOption = Some("yeS")
          ), ontologyDAO)
          val expected = Map(
            AttributeName.withLibraryNS("NAGR") -> AttributeBoolean(true),
            AttributeName.withLibraryNS("NCTRL") -> AttributeBoolean(true)
          )
          assertResult(expected) {attrs}
        }
        "should handle 'no' string values from DUOS as boolean FALSEs" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            aggregateResearch = Some("No"),
            controlSetOption = Some("NO")
          ), ontologyDAO)
          val expected = Map(
            AttributeName.withLibraryNS("NAGR") -> AttributeBoolean(false),
            AttributeName.withLibraryNS("NCTRL") -> AttributeBoolean(false)
          )
          assertResult(expected) {attrs}
        }
        "should handle non-'yes' or 'no' string values from DUOS as invalid (Nones)" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            aggregateResearch = Some("yessir"),
            controlSetOption = Some("nopers")
          ), ontologyDAO)
          assert(attrs.isEmpty)
        }
        "should translate disease ontology nodes, including labels" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            diseaseRestrictions = Some(Seq(
              "http://purl.obolibrary.org/obo/DOID_2531", // hematologic cancer
              "http://purl.obolibrary.org/obo/DOID_1240", // leukemia 1510
              "http://purl.obolibrary.org/obo/DOID_4325" // Ebola hemorrhagic fever
            ))
          ), ontologyDAO)
          val expected = Map(
            AttributeName.withLibraryNS("DS_URL") -> AttributeValueList(Seq(
              AttributeString("http://purl.obolibrary.org/obo/DOID_2531"),
              AttributeString("http://purl.obolibrary.org/obo/DOID_1240"),
              AttributeString("http://purl.obolibrary.org/obo/DOID_4325")
            )),
            AttributeName.withLibraryNS("DS") -> AttributeValueList(Seq(
              AttributeString("hematologic cancer"),
              AttributeString("leukemia"),
              AttributeString("Ebola hemorrhagic fever")
            ))
          )
          assertResult(expected) {attrs}
        }
        "should translate disease ontology nodes and use node id when label not found" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            diseaseRestrictions = Some(Seq(
              "http://purl.obolibrary.org/obo/DOID_2531", // hematologic cancer
              "http://purl.obolibrary.org/obo/DOID_1510", // -- not found in mock dao --
              "http://purl.obolibrary.org/obo/DOID_4325" // Ebola hemorrhagic fever
            ))
          ), ontologyDAO)
          val expected = Map(
            AttributeName.withLibraryNS("DS_URL") -> AttributeValueList(Seq(
              AttributeString("http://purl.obolibrary.org/obo/DOID_2531"),
              AttributeString("http://purl.obolibrary.org/obo/DOID_1510"),
              AttributeString("http://purl.obolibrary.org/obo/DOID_4325")
            )),
            AttributeName.withLibraryNS("DS") -> AttributeValueList(Seq(
              AttributeString("hematologic cancer"),
              AttributeString("http://purl.obolibrary.org/obo/DOID_1510"),
              AttributeString("Ebola hemorrhagic fever")
            ))
          )
          assertResult(expected) {attrs}
        }
        "should handle empty seq of disease ontology nodes" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            diseaseRestrictions = Some(Seq.empty[String])
          ), ontologyDAO)
          assert(attrs.isEmpty)
        }
        "should handle populationRestrictions string lists" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            pediatric = Some(true)
          ), ontologyDAO)
          val expected = Map(
            AttributeName.withLibraryNS("RS-PD") -> AttributeBoolean(true)
          )
          assertResult(expected) {attrs}
        }
        "should handle empty populationRestrictions" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            populationRestrictions = Some(Seq.empty[String])
          ), ontologyDAO)

          assert(attrs.isEmpty)
        }
        "should handle gender = Male" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            gender = Some("Male")
          ), ontologyDAO)
          val expected = Map(
            AttributeName.withLibraryNS("RS-G") -> AttributeBoolean(true),
            AttributeName.withLibraryNS("RS-M") -> AttributeBoolean(true)
          )
          assertResult(expected) {attrs}
        }
        "should handle gender = Female" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            gender = Some("Female")
          ), ontologyDAO)
          val expected = Map(
            AttributeName.withLibraryNS("RS-G") -> AttributeBoolean(true),
            AttributeName.withLibraryNS("RS-FM") -> AttributeBoolean(true)
          )
          assertResult(expected) {attrs}
        }
        "should ignore invalid gender values" in {
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
            gender = Some("invalid")
          ), ontologyDAO)

          assert(attrs.isEmpty)
        }
        "should ignore the DUOS keys that FireCloud doesn't implement" in {

          // The properties that are commented out are the ones that FireCloud implements.
          val attrs = generateStructuredUseRestrictionAttribute(new DuosDataUse(
//            generalUse = None,
//            hmbResearch = None,
//            diseaseRestrictions = None,
//            commercialUse = None,
//            methodsResearch = None,
//            aggregateResearch = None,
//            controlSetOption = None,
//            pediatric = None,
//            populationRestrictions = None,
//            gender = None,
            populationOriginsAncestry = Some(true),
            populationStructure = Some(true),
            vulnerablePopulations = Some(true),
            dateRestriction = Some("today"),
            recontactingDataSubjects = Some(true),
            recontactMay = Some("sure"),
            recontactMust = Some("yes"),
            genomicPhenotypicData = Some("pheno"),
            otherRestrictions = Some(true),
            cloudStorage = Some("cloud"),
            ethicsApprovalRequired = Some(true),
            geographicalRestrictions = Some("nowhere"),
            other = Some("other"),
            illegalBehavior = Some(true),
            addiction = Some(true),
            sexualDiseases = Some(true),
            stigmatizeDiseases = Some(true),
            psychologicalTraits = Some(true),
            nonBiomedical = Some(true)
          ), ontologyDAO)
          assert(attrs.isEmpty)
        }
      }
    }
    "when annotating a workspace with ORSP-based data use" - {
      val mockDUCodes = Map(
        AttributeName.withLibraryNS("GRU") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("HMB") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("NCU") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("NPU") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("NMDS") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("NAGR") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("NCTRL") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("RS-PD") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("IRB") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("RS-G") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("RS-FM") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("RS-M") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("DS_URL") -> AttributeValueList(Seq(AttributeString("one"),AttributeString("two"))),
        AttributeName.withLibraryNS("DS_URL") -> AttributeValueList(Seq(AttributeString("five"),AttributeString("six"))),
        AttributeName.withLibraryNS("consentCodes") -> AttributeValueList(Seq(AttributeString("seven"),AttributeString("eight"))),
        AttributeName.withLibraryNS("structuredUseRestriction") -> AttributeValueRawJson("""{"foo":"bar"}""")
      )


      "should add attributes when no previous attributes exist" in {
        val existing = Map.empty[AttributeName,Attribute]
        val preferred = Map(
          AttributeName.withLibraryNS("GRU") -> AttributeBoolean(true)
        )
        val actual = replaceDataUseAttributes(existing, preferred)
        val expected = Map(
          AttributeName.withLibraryNS("GRU") -> AttributeBoolean(true)
        )
        assertResult(expected) {actual}
      }
      "should not add attributes when no new attributes exist" in {
        val existing = Map.empty[AttributeName,Attribute]
        val preferred = Map.empty[AttributeName,Attribute]
        val actual = replaceDataUseAttributes(existing, preferred)
        val expected = Map.empty[AttributeName,Attribute]
        assertResult(expected) {actual}
      }
      "should preserve pre-existing non-DU attributes" in {
        val existing = Map(
          AttributeName.withDefaultNS("description") -> AttributeString("my description"),
          AttributeName.withLibraryNS("datasetName") -> AttributeString("my dataset")
        )
        val preferred = Map(
          AttributeName.withLibraryNS("GRU") -> AttributeBoolean(true)
        )
        val actual = replaceDataUseAttributes(existing, preferred)
        val expected = Map(
          AttributeName.withDefaultNS("description") -> AttributeString("my description"),
          AttributeName.withLibraryNS("datasetName") -> AttributeString("my dataset"),
          AttributeName.withLibraryNS("GRU") -> AttributeBoolean(true)
        )
        assertResult(expected) {actual}
      }
      "should overwrite pre-existing DU attributes" in {
        val existing = Map(
          AttributeName.withLibraryNS("HMB") -> AttributeBoolean(true)
        )
        val preferred = Map(
          AttributeName.withLibraryNS("HMB") -> AttributeBoolean(false)
        )
        val actual = replaceDataUseAttributes(existing, preferred)
        val expected = Map(
          AttributeName.withLibraryNS("HMB") -> AttributeBoolean(false)
        )
        assertResult(expected) {actual}
      }
      "should remove pre-existing DU attributes when adding new" in {
        val existing = Map(
          AttributeName.withLibraryNS("HMB") -> AttributeBoolean(true),
          AttributeName.withLibraryNS("NAGR") -> AttributeBoolean(true)
        )
        val preferred = Map(
          AttributeName.withLibraryNS("HMB") -> AttributeBoolean(false)
        )
        val actual = replaceDataUseAttributes(existing, preferred)
        val expected = Map(
          AttributeName.withLibraryNS("HMB") -> AttributeBoolean(false)
        )
        assertResult(expected) {actual}
      }
      "should remove pre-existing DU attributes even when adding nothing" in {
        val existing = mockDUCodes
        val preferred = Map.empty[AttributeName,Attribute]
        val actual = replaceDataUseAttributes(existing, preferred)
        val expected = Map.empty[AttributeName,Attribute]
        assertResult(expected) {actual}
      }
    }

  }


  //////////////////
  // Utility methods
  //////////////////


  private def getValuesFromAttributeValueListAsAttribute(attrs: Option[Attribute]): Seq[String] = {
    (attrs collect {
      case x: AttributeValueList => x.list.collect {
        case a: AttributeString => a.value
      }
    }).getOrElse(Seq.empty[String])
  }

  private def makeDurFromWorkspace(ds: WorkspaceDetails, ontologyDAO: MockOntologyDAO): DataUseRestriction = {
    val attrs = generateStructuredAndDisplayAttributes(ds, ontologyDAO).structured
    val durAtt: Attribute = attrs.getOrElse(structuredUseRestrictionAttributeName, AttributeNull)
    durAtt.toJson.convertTo[DataUseRestriction]
  }

  private def checkBooleanTrue(dur: DataUseRestriction, fieldName: String): Boolean = {
    getFieldMap(dur).getOrElse(fieldName, false).asInstanceOf[Boolean]
  }

  private def checkListValues(dur: DataUseRestriction, fieldName: String): Unit = {
    val fieldValue: Seq[String] = getFieldMap(dur).getOrElse(fieldName, Seq.empty[String]).asInstanceOf[Seq[String]]
    listValues should contain theSameElementsAs fieldValue
  }

  private def checkDiseaseValues(dur: DataUseRestriction, fieldName: String): Unit = {
    val fieldValue: Seq[Int] = getFieldMap(dur).getOrElse(fieldName, Seq.empty[Int]).asInstanceOf[Seq[Int]]
    diseaseValuesInts should contain theSameElementsAs fieldValue
  }

  private def getFieldMap(dur: DataUseRestriction): Map[String, Object] = {
    dur.getClass.getDeclaredFields map { f =>
      f.setAccessible(true)
      getFieldName(f) -> f.get(dur)
    } toMap
  }

  // Since we have dashes in DUR field names, the value that comes back from Field.getName
  // looks like "RS$minusPOP" instead of "RS-POP"
  private def getFieldName(f: Field): String = {
    f.getName.replace("$minus", "-")
  }

}
