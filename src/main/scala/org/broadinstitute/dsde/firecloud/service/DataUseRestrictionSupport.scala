package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.AttributeNameFormat
import org.broadinstitute.dsde.rawls.model._
import spray.json.DefaultJsonProtocol._
import spray.json._

trait DataUseRestrictionSupport {

  private val booleanCodes = List("GRU", "HMB", "NCU", "NPU", "NDMS", "NAGR", "NCTRL","RS-PD")
  private val listCodes = List("DS", "RS-POP")
  private val durNames = booleanCodes ++ listCodes ++ "RS-G"

  /**
    * This method looks at all of the library attributes that are associated to Consent Codes and
    * builds a set of Data Use Restriction attributes from the values. Most are boolean values, some are lists
    * of strings, and in the case of gender, we need to transform a string value to a trio of booleans.
    *
    * @param workspace The Workspace
    * @return 'dataUseRestriction' Attribute Map
    */
  def generateDataUseRestriction(workspace: Workspace): Map[AttributeName, Attribute] = {

    implicit val impAttributeFormat: AttributeFormat with PlainArrayAttributeListSerializer =
      new AttributeFormat with PlainArrayAttributeListSerializer

    val libraryAttrs = workspace.attributes.filter { case (attr, value) => durNames.contains(attr.name) }

    if (libraryAttrs.isEmpty) {
      Map(AttributeName.withLibraryNS("dataUseRestriction") -> AttributeNull)
    } else {
      val existingAttrs: Map[AttributeName, AttributeValue] = libraryAttrs.flatMap {
        // Handle the String->Boolean case first
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
        // Everything else can be added as is.
        case (attr: AttributeName, value: AttributeValue) => Map(AttributeName.withDefaultNS(attr.name) -> value)
      }

      val existingKeyNames = existingAttrs.keys.map(_.name).toSeq

      // Missing boolean codes with default false
      val booleanAttrs: Map[AttributeName, AttributeBoolean] = (booleanCodes ++ List("RS-G", "RS-FM", "RS-M")).
        filter(!existingKeyNames.contains(_)).
        flatMap { code =>
          Map(AttributeName.withDefaultNS(code) -> AttributeBoolean(false))
        }.toMap

      // Missing list codes with default empty lists
      val listAttrs = listCodes.
        filter(!existingKeyNames.contains(_)).
        flatMap { code =>
          Map(AttributeName.withDefaultNS(code) -> AttributeValueList(Seq.empty))
        }.toMap

      val allAttrs = existingAttrs ++ booleanAttrs ++ listAttrs

      Map(AttributeName.withLibraryNS("dataUseRestriction") ->
        AttributeValueRawJson.apply(allAttrs.toJson.compactPrint))
    }

  }

}
