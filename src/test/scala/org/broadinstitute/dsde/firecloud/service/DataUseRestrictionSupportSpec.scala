package org.broadinstitute.dsde.firecloud.service

import java.lang.reflect.Field

import org.broadinstitute.dsde.firecloud.service.DataUseRestrictionTestFixtures._
import org.broadinstitute.dsde.rawls.model.{ManagedGroupRef, Workspace, _}
import org.scalatest.{FreeSpec, Matchers}
import spray.json._

import scala.language.postfixOps

class DataUseRestrictionSupportSpec extends FreeSpec with Matchers with DataUseRestrictionSupport {

  "DataUseRestrictionSupport" - {

    "when there are library data use restriction fields" - {

      "dataset should have a fully populated data use restriction attribute" in {
        allDatasets.map { ds =>
          val attrs = generateStructuredUseRestriction(ds)
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
            dur.NAGR should be (true)
          } else {
            dur.NAGR should be (false)
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

        listCodes.map { code =>
          val dur: DataUseRestriction = durs(code)
          checkListValues(dur, code) should be(true)
        }
      }

    }

    "when there are no library data use restriction fields" - {

      "dataset should not have any data use restriction for empty attributes" in {
        val workspace = mkWorkspace(Map.empty[AttributeName, Attribute], "empty")
        val attrs = generateStructuredUseRestriction(workspace)
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
        val attrs = generateStructuredUseRestriction(workspace)
        attrs should be(empty)
      }

    }

  }


  //////////////////
  // Utility methods
  //////////////////


  private def makeDurFromWorkspace(ds: Workspace): DataUseRestriction = {
    val attrs = generateStructuredUseRestriction(ds)
    val durAtt: Attribute = attrs.getOrElse(structuredUseRestrictionAttributeName, AttributeNull)
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

  // Since we have dashes in DUR field names, the value that comes back from Field.getName
  // looks like "RS$minusPOP" instead of "RS-POP"
  private def getFieldName(f: Field): String = { f.getName.replace("$minus", "-") }

}
