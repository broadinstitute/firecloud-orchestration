package org.broadinstitute.dsde.firecloud.service

import java.lang.reflect.Field
import java.util.UUID

import org.broadinstitute.dsde.firecloud.integrationtest.SearchResultValidation
import DataUseRestrictionTestFixture._
import org.broadinstitute.dsde.rawls.model.{ManagedGroupRef, Workspace, _}
import org.joda.time.DateTime
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.language.postfixOps

object DataUseRestrictionTestFixture {

  case class DataUseRestriction(
    GRU: Boolean = false,
    HMB: Boolean = false,
    DS: Seq[String] = Seq.empty[String],
    NCU: Boolean = false,
    NPU: Boolean = false,
    NDMS: Boolean = false,
    NAGR: Boolean = false,
    NCTRL: Boolean = false,
    `RS-PD`: Boolean = false,
    `RS-G`: Boolean = false,
    `RS-FM`: Boolean = false,
    `RS-M`: Boolean = false,
    `RS-POP`: Seq[String] = Seq.empty[String])


  // Datasets are named by the code for easier identification in tests
  val booleanCodes: Seq[String] = Seq("GRU", "HMB", "NCU", "NPU", "NDMS", "NCTRL", "RS-PD")
  val booleanDatasets: Seq[Workspace] = booleanCodes.map { code =>
    val attributes = Map(AttributeName.withLibraryNS(code) -> AttributeBoolean(true))
    mkWorkspace(attributes, code)
  }

  val listCodes: Seq[String] = Seq("DS", "RS-POP")
  val listValues: Seq[String] = Seq("TERM-1", "TERM-2")
  val listDatasets: Seq[Workspace] = listCodes.map { code =>
    val attributes = Map(AttributeName.withLibraryNS(code) -> AttributeValueList(listValues.map(AttributeString)))
    mkWorkspace(attributes, code)
  }

  // Gender datasets are named by the gender value for easier identification in tests
  val genderVals: Seq[String] = Seq("Female", "Male", "N/A")
  val genderDatasets: Seq[Workspace] = genderVals.map { gender =>
    val attributes = Map(AttributeName.withLibraryNS("RS-G") -> AttributeString(gender))
    mkWorkspace(attributes, gender)
  }

  val nagrVals: Seq[String] = Seq("Yes", "No", "Unspecified")
  val nagrDatasets: Seq[Workspace] = nagrVals.map { value =>
    val attributes = Map(AttributeName.withLibraryNS("NAGR") -> AttributeString(value))
    mkWorkspace(attributes, value)
  }

  val allDatasets: Seq[Workspace] = booleanDatasets ++ listDatasets ++ genderDatasets ++ nagrDatasets

  def mkWorkspace(attributes: Map[AttributeName, Attribute], wsName: String): Workspace = {
    val testUUID: UUID = UUID.randomUUID()
    Workspace(
      workspaceId=testUUID.toString,
      namespace="testWorkspaceNamespace",
      name=wsName,
      authorizationDomain=Set.empty[ManagedGroupRef],
      isLocked=false,
      createdBy="createdBy",
      createdDate=DateTime.now(),
      lastModified=DateTime.now(),
      attributes=attributes,
      bucketName="bucketName",
      accessLevels=Map.empty,
      authDomainACLs=Map())
  }

}

class DataUseRestrictionSupportSpec extends FreeSpec with SearchResultValidation with Matchers with BeforeAndAfterAll with DataUseRestrictionSupport {

  implicit val impAttributeFormat: AttributeFormat with PlainArrayAttributeListSerializer = new AttributeFormat with PlainArrayAttributeListSerializer
  implicit val impDataUseRestriction: RootJsonFormat[DataUseRestriction] = jsonFormat13(DataUseRestriction)

  "DataUseRestrictionSupport" - {

    "when there are library data use restriction fields" - {

      "dataset should have a fully populated data use restriction attribute" in {
        allDatasets.map { ds =>
          val attrs = generateDataUseRestriction(ds)
          val durAtt: Attribute = attrs.getOrElse(dataUseRestrictionAttributeName, AttributeNull)
          durAtt shouldNot be(AttributeNull)
          val dur: DataUseRestriction = makeDURFromWorkspace(ds)
          dur shouldNot be(null)
        }
      }

      "dur should have appropriate gender codes populated" in {
        genderDatasets.map { ds =>
          val dur: DataUseRestriction = makeDURFromWorkspace(ds)
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
          val dur: DataUseRestriction = makeDURFromWorkspace(ds)
          if (ds.name.equalsIgnoreCase("Yes")) {
            dur.NAGR should be (true)
          } else {
            dur.NAGR should be (false)
          }
        }
      }

      "dataset should have a true value for the consent code for which it was specified" in {
        val durs: Map[String, DataUseRestriction] = booleanDatasets.flatMap { ds =>
          Map(ds.name -> makeDURFromWorkspace(ds))
        }.toMap

        booleanCodes.map { code =>
          val dur: DataUseRestriction = durs(code)
          checkBooleanTrue(dur, code) should be(true)
        }
      }

      "dataset should have the correct list values for the consent code for which it was specified" in {
        val durs: Map[String, DataUseRestriction] = listDatasets.flatMap { ds =>
          Map(ds.name -> makeDURFromWorkspace(ds))
        }.toMap

        listCodes.map { code =>
          val dur: DataUseRestriction = durs(code)
          checkListValues(dur, code) should be(true)
        }
      }

    }

    "when there are no library data use restriction fields" - {

      "dataset should not have any data use restriction for empty attributes" in {
        val workspace = mkWorkspace(Map.empty[AttributeName, Attribute], "empty")
        val attrs = generateDataUseRestriction(workspace)
        attrs should be(empty)
      }

      "dataset should not have any data use restriction for non-library attributes" in {
        val nonLibraryAttributes = Map(
          AttributeName.withDefaultNS("name") -> AttributeString("one"),
          AttributeName.withDefaultNS("namespace") -> AttributeString("two"),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString("three"),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString("one"), AttributeString("two"), AttributeString("three")))
        )
        val workspace = mkWorkspace(nonLibraryAttributes, "non-library")
        val attrs = generateDataUseRestriction(workspace)
        attrs should be(empty)
      }

    }

  }


  //////////////////////////////////
  // Utility methods
  //////////////////////////////////


  private def makeDURFromWorkspace(ds: Workspace): DataUseRestriction = {
    val attrs = generateDataUseRestriction(ds)
    val durAtt: Attribute = attrs.getOrElse(dataUseRestrictionAttributeName, AttributeNull)
    durAtt.toJson.convertTo[DataUseRestriction]
  }

  private def checkBooleanTrue(dur: DataUseRestriction, fieldName: String): Boolean = {
    getFieldMap(dur).getOrElse(fieldName, false).asInstanceOf[Boolean]
  }

  private def checkListValues(dur: DataUseRestriction, fieldName: String): Boolean = {
    val fieldValue: Seq[String] = getFieldMap(dur).getOrElse(fieldName, Seq.empty[String]).asInstanceOf[Seq[String]]
    listValues.forall { lv => fieldValue.contains(lv) }
  }

  private def getFieldMap(dur: DataUseRestriction): Map[String, Object] = {
    dur.getClass.getDeclaredFields map { f =>
      f.setAccessible(true)
      getFieldName(f) -> f.get(dur)
    } toMap
  }

  // Since we have dashes in field names, the value that comes back from Field.getName
  // looks like "RS$minusPOP" instead of "RS-POP"
  private def getFieldName(f: Field): String = { f.getName.replace("$minus", "-") }

}
