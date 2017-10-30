package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.rawls.model._
import spray.json._
import spray.json.DefaultJsonProtocol._

trait DataUseRestrictionSupport {

  private val booleanCodes = List("GRU", "HMB", "NCU", "NPU", "NDMS", "NAGR", "NCTRL","RS-PD")
  private val listCodes = List("DS", "RS-POP")
  private val durNames = booleanCodes ++ listCodes ++ "RS-G"

  /**
    * This method looks at all of the library attributes that are associated to Consent Codes and
    * builds a Data Use Restriction object from the values. Most are boolean values, some are lists
    * of strings, and in the case of gender, we need to transform a string value to a trio of booleans.
    *
    * @param workspace The Workspace
    * @return 'dataUseRestriction' Attribute Map
    */
  def generateDataUseRestriction(workspace: Workspace): Map[AttributeName, Attribute] = {

    val libraryAttrs = workspace.attributes.filter{case (attr, value) => durNames.contains(attr.name)}

    if (libraryAttrs.isEmpty) {
      Map(AttributeName.withLibraryNS("dataUseRestriction") -> AttributeNull)
    } else {
      val existingAttrs: Map[AttributeName, AttributeValue] = libraryAttrs.flatMap {
        case (attr: AttributeName, value: AttributeString) if attr.name.equals("RS-G") =>
          value.value match {
            case x if x.equalsIgnoreCase("female") =>
              Map(AttributeName.withDefaultNS("RS-G") -> AttributeBoolean(true),
                AttributeName.withDefaultNS("RS-FM") -> AttributeBoolean(true),
                AttributeName.withDefaultNS("RS-M") -> AttributeBoolean(false))
            case x if x.equalsIgnoreCase("male") =>
              Map(AttributeName.withDefaultNS("RS-G") -> AttributeBoolean(true),
                AttributeName.withDefaultNS("RS-FM") -> AttributeBoolean(false),
                AttributeName.withDefaultNS("RS-M") -> AttributeBoolean(true))
            case _ =>
              Map(AttributeName.withDefaultNS("RS-G") -> AttributeBoolean(false),
                AttributeName.withDefaultNS("RS-FM") -> AttributeBoolean(false),
                AttributeName.withDefaultNS("RS-M") -> AttributeBoolean(false))
          }
        case (attr: AttributeName, value: AttributeValue) => Map(AttributeName.withDefaultNS(attr.name) -> value)
      }

      // Missing boolean codes with default false
      val booleanAttrs: Map[AttributeName, AttributeBoolean] = (booleanCodes ++ List("RS-G", "RS-FM", "RS-M")).flatMap { code =>
        val attr = AttributeName.withDefaultNS(code)
        code match {
          case x if !existingAttrs.isDefinedAt(attr) => Map(attr -> AttributeBoolean(false))
        }
      }.toMap

      // Missing list codes with default empty lists
      val listAttrs: Map[AttributeName, AttributeValueList] = listCodes.flatMap { code =>
        val attr = AttributeName.withDefaultNS(code)
        code match {
          case x if !existingAttrs.isDefinedAt(attr) => Map(attr -> AttributeValueList(Seq.empty))
        }
      }.toMap

      val allAttrs = existingAttrs ++ booleanAttrs ++ listAttrs

      Map(AttributeName.withLibraryNS("dataUseRestriction") ->
        AttributeValueRawJson.apply(allAttrs.toJson.compactPrint))
    }

  }

}
