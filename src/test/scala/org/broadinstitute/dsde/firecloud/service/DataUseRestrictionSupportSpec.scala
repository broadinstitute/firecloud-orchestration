package org.broadinstitute.dsde.firecloud.service

import java.lang.reflect.Field

import org.broadinstitute.dsde.firecloud.model.DataUse
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionTestFixtures._
import org.broadinstitute.dsde.rawls.model.{ManagedGroupRef, Workspace, _}
import org.scalatest.{FreeSpec, Matchers}
import spray.json._

import scala.language.postfixOps

class DataUseRestrictionSupportSpec extends FreeSpec with Matchers with DataUseRestrictionSupport {

  "DataUseRestrictionSupport" - {

    "Structured Use Restriction" - {

      "when there are library data use restriction fields" - {

        "dataset should have a fully populated data use restriction attribute" in {
          allDatasets.map { ds =>
            val attrs: Map[AttributeName, Attribute] = generateStructuredUseRestrictionAttribute(ds)
            val durAtt: Attribute = attrs.getOrElse(structuredUseRestrictionAttributeName, AttributeNull)
            durAtt shouldNot be(AttributeNull)
            val dur: DataUseRestriction = makeDurFromWorkspace(ds)
            dur shouldNot be(null)
          }
        }

        "dur should have appropriate gender codes populated" in {
          genderDatasets.map { ds =>
            val dur: DataUseRestriction = makeDurFromWorkspace(ds)
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
            val dur: DataUseRestriction = makeDurFromWorkspace(ds)
            if (ds.name.equalsIgnoreCase("Yes")) {
              dur.NAGR should be(true)
            } else {
              dur.NAGR should be(false)
            }
          }
        }

        "dataset should have a true value for the consent code for which it was specified" in {
          val durs: Map[String, DataUseRestriction] = booleanDatasets.flatMap { ds =>
            Map(ds.name -> makeDurFromWorkspace(ds))
          }.toMap

          booleanCodes.map { code =>
            val dur: DataUseRestriction = durs(code)
            checkBooleanTrue(dur, code) should be(true)
          }
        }

        "dataset should have the correct list values for the consent code for which it was specified" in {
          val durs: Map[String, DataUseRestriction] = listDatasets.flatMap { ds =>
            Map(ds.name -> makeDurFromWorkspace(ds))
          }.toMap

          listCodes.foreach { code =>
            val dur: DataUseRestriction = durs(code)
            checkListValues(dur, code)
          }
        }

        "dataset should have the correct disease values for the consent code for which it was specified" in {
          val durs: Map[String, DataUseRestriction] = diseaseDatasets.flatMap { ds =>
            Map(ds.name -> makeDurFromWorkspace(ds))
          }.toMap

          diseaseCodes.foreach { code =>
            val dur: DataUseRestriction = durs(code)
            checkDiseaseValues(dur, code)
          }
        }

      }

      "when there are no library data use restriction fields" - {

        "dataset should not have any data use restriction for empty attributes" in {
          val workspace: Workspace = mkWorkspace(Map.empty[AttributeName, Attribute], "empty", "empty")
          val attrs: Map[AttributeName, Attribute] = generateStructuredUseRestrictionAttribute(workspace)
          attrs should be(empty)
        }

        "dataset should not have any data use restriction for non-library attributes" in {
          val nonLibraryAttributes = Map(
            AttributeName.withDefaultNS("name") -> AttributeString("one"),
            AttributeName.withDefaultNS("namespace") -> AttributeString("two"),
            AttributeName.withDefaultNS("workspaceId") -> AttributeString("three"),
            AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString("one"), AttributeString("two"), AttributeString("three")))
          )
          val workspace: Workspace = mkWorkspace(nonLibraryAttributes, "non-library", "non-library")
          val attrs: Map[AttributeName, Attribute] = generateStructuredUseRestrictionAttribute(workspace)
          attrs should be(empty)
        }

      }

    }

    "Display Use Restriction" - {

      "when there are library data use restriction fields" - {

        "valid datasets should have some form of data use display attribute" in {
          validDisplayDatasets.map { ds =>
            val attrs: Map[AttributeName, Attribute] = generateUseRestrictionDisplayAttribute(ds)
            val codes: Seq[String] = getValuesFromAttributeValueListAsAttribute(attrs.get(dataUseDisplayAttributeName))
            codes shouldNot be(empty)
          }
        }

        "datasets with single boolean code should have that single display code" in {
          booleanDatasets.map { ds =>
            val attrs: Map[AttributeName, Attribute] = generateUseRestrictionDisplayAttribute(ds)
            val codes: Seq[String] = getValuesFromAttributeValueListAsAttribute(attrs.get(dataUseDisplayAttributeName))
            // Boolean datasets are named with the same code value
            codes should contain(ds.name)
            codes.size should be(1)
          }
        }

        "'EVERYTHING' dataset should have the right codes" in {
          val attrs: Map[AttributeName, Attribute] = generateUseRestrictionDisplayAttribute(everythingDataset.head)
          val codes: Seq[String] = getValuesFromAttributeValueListAsAttribute(attrs.get(dataUseDisplayAttributeName))
          (booleanCodes ++ Seq("RS-G", "RS-FM", "NAGR")).map { c => codes should contain(c) }
          diseaseValuesLabels.map { c => codes should contain("DS:" + c) }
          Seq("RS-M", "RS-POP").map { c => codes shouldNot contain(c) }
        }

        "'TOP_THREE' dataset should have the right codes" in {
          val attrs: Map[AttributeName, Attribute] = generateUseRestrictionDisplayAttribute(topThreeDataset.head)
          val codes: Seq[String] = getValuesFromAttributeValueListAsAttribute(attrs.get(dataUseDisplayAttributeName))
          Seq("GRU", "HMB").map { c => codes should contain(c) }
          diseaseValuesLabels.map { c => codes should contain("DS:" + c) }
          Seq("NCU", "NPU", "NDMS", "NCTRL", "RS-PD", "NAGR", "RS-G", "RS-FM", "RS-M", "RS-POP").map { c => codes shouldNot contain(c) }
        }
      }

      "when there are missing/invalid library data use restriction terms" - {

        "dataset should not have any data use display codes for empty attributes" in {
          val workspace: Workspace = mkWorkspace(Map.empty[AttributeName, Attribute], "empty", "empty")
          val attrs: Map[AttributeName, Attribute] = generateUseRestrictionDisplayAttribute(workspace)
          attrs should be(empty)
        }

        "dataset should not have any data use restriction for non-library attributes" in {
          val nonLibraryAttributes: Map[AttributeName, Attribute] = Map(
            AttributeName.withDefaultNS("name") -> AttributeString("one"),
            AttributeName.withDefaultNS("namespace") -> AttributeString("two"),
            AttributeName.withDefaultNS("workspaceId") -> AttributeString("three"),
            AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString("one"), AttributeString("two"), AttributeString("three")))
          )
          val workspace: Workspace = mkWorkspace(nonLibraryAttributes, "non-library", "non-library")
          val attrs: Map[AttributeName, Attribute] = generateUseRestrictionDisplayAttribute(workspace)
          attrs should be(empty)
        }

        "dataset should not have any data use display codes for missing/not-found disease terms" in {
          listDatasets.map { ds =>
            val attrs: Map[AttributeName, Attribute] = generateUseRestrictionDisplayAttribute(ds)
            attrs should be(empty)
          }
        }

      }

    }
  }


  //////////////////
  // Utility methods
  //////////////////


  private def getValuesFromAttributeValueListAsAttribute(attrs: Option[Attribute]): Seq[String] = {
    val results: Seq[String] = attrs match {
      case Some(att) =>
        att match {
          case x: AttributeValueList => x.list.map {
            case avl@(a: AttributeString) => a.value
            case _ => ""
          }
          case unhandled => Seq.empty[String]
        }
      case None => Seq.empty[String]
    }
    results.distinct.filter(_.nonEmpty)
  }

  private def makeDurFromWorkspace(ds: Workspace): DataUseRestriction = {
    val attrs: Map[AttributeName, Attribute] = generateStructuredUseRestrictionAttribute(ds)
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
