package org.broadinstitute.dsde.firecloud.service

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
  val booleanCodes: Seq[String] = Seq("GRU", "HMB", "NCU", "NPU", "NDMS", "NAGR", "NCTRL","RS-PD")
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

  val allDatasets: Seq[Workspace] = booleanDatasets ++ listDatasets ++ genderDatasets

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
          val attrs = generateDataUseRestriction(ds)
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

  private def makeDURFromWorkspace(ds: Workspace): DataUseRestriction = {
    val attrs = generateDataUseRestriction(ds)
    val durAtt: Attribute = attrs.getOrElse(dataUseRestrictionAttributeName, AttributeNull)
    durAtt.toJson.convertTo[DataUseRestriction]
  }

  private def checkBooleanTrue(dur: DataUseRestriction, fieldName: String): Boolean = {
    val fieldMap: Map[String, Boolean] = dur.getClass.getDeclaredFields map { f =>
      f.setAccessible(true)
      // TODO: Get rid of warning ... can't match on boolean type
      if (f.get(dur).isInstanceOf[Boolean]) {
        f.getName.replace("$minus", "-") -> f.get(dur).asInstanceOf[Boolean]
      } else {
        f.getName.replace("$minus", "-") -> false
      }
    } toMap

    fieldMap(fieldName)
  }

  private def checkListValues(dur: DataUseRestriction, fieldName: String): Boolean = {
    val fieldMap: Map[String, Seq[String]] = dur.getClass.getDeclaredFields map { f =>
      f.setAccessible(true)
      f.get(dur) match {
          // TODO: Type erasure warning
        case x: Seq[String] => f.getName.replace("$minus", "-") -> x
        case _ => f.getName.replace("$minus", "-") -> Seq.empty[String]
      }
    } toMap

    listValues.forall { lv => fieldMap(fieldName).contains(lv)}

  }

}
