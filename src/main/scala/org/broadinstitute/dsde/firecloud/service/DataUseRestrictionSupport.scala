package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.AttributeNameFormat
import org.broadinstitute.dsde.rawls.model._
import spray.json.DefaultJsonProtocol._
import spray.json._

trait DataUseRestrictionSupport {

  private val booleanCodes = List("GRU", "HMB", "NCU", "NPU", "NDMS", "NAGR", "NCTRL", "RS-PD")
  private val listCodes = List("DS", "RS-POP")
  private val durNames = booleanCodes ++ listCodes ++ List("RS-G")

  val structuredUseRestrictionAttributeName: AttributeName = AttributeName.withLibraryNS("structuredUseRestriction")

  /**
    * This method looks at all of the library attributes that are associated to Consent Codes and
    * builds a set of structured Data Use Restriction attributes from the values. Most are boolean
    * values, some are lists of strings, and in the case of gender, we need to transform a string
    * value to a trio of booleans.
    *
    * In the case of incorrectly tagged data use attributes (i.e. GRU = "Yes"), we ignore them and only
    * populate using values specified in attribute-definitions.json
    *
    * @param workspace The Workspace
    * @return A structured data use restriction Attribute Map
    */
  def generateStructuredUseRestriction(workspace: Workspace): Map[AttributeName, Attribute] = {

    implicit val impAttributeFormat: AttributeFormat with PlainArrayAttributeListSerializer =
      new AttributeFormat with PlainArrayAttributeListSerializer

    val libraryAttrs = workspace.attributes.filter { case (attr, value) => durNames.contains(attr.name) }

    if (libraryAttrs.isEmpty) {
      Map.empty
    } else {
      val existingAttrs: Map[AttributeName, Attribute] = libraryAttrs.flatMap {
        // Handle the known String->Boolean conversion cases first
        case (attr: AttributeName, value: AttributeString) =>
          attr.name match {
            case name if name.equalsIgnoreCase("NAGR") =>
              value.value match {
                case v if v.equalsIgnoreCase("yes") => Map(AttributeName.withDefaultNS("NAGR") -> AttributeBoolean(true))
                case _ => Map(AttributeName.withDefaultNS("NAGR") -> AttributeBoolean(false))
              }
            case name if name.equalsIgnoreCase("RS-G") =>
              value.value match {
                case v if v.equalsIgnoreCase("female") =>
                  Map(AttributeName.withDefaultNS("RS-G") -> AttributeBoolean(true),
                    AttributeName.withDefaultNS("RS-FM") -> AttributeBoolean(true),
                    AttributeName.withDefaultNS("RS-M") -> AttributeBoolean(false))
                case v if v.equalsIgnoreCase("male") =>
                  Map(AttributeName.withDefaultNS("RS-G") -> AttributeBoolean(true),
                    AttributeName.withDefaultNS("RS-FM") -> AttributeBoolean(false),
                    AttributeName.withDefaultNS("RS-M") -> AttributeBoolean(true))
                case _ =>
                  Map(AttributeName.withDefaultNS("RS-G") -> AttributeBoolean(false),
                    AttributeName.withDefaultNS("RS-FM") -> AttributeBoolean(false),
                    AttributeName.withDefaultNS("RS-M") -> AttributeBoolean(false))
              }
            case _ => Map.empty[AttributeName, Attribute]
          }
        // Handle only what is correctly tagged and ignore anything improperly tagged
        case (attr: AttributeName, value: AttributeBoolean) => Map(AttributeName.withDefaultNS(attr.name) -> value)
        case (attr: AttributeName, value: AttributeValueList) => Map(AttributeName.withDefaultNS(attr.name) -> value)
        case _ => Map.empty[AttributeName, Attribute]
      }

      val existingKeyNames = existingAttrs.keys.map(_.name).toSeq

      // Missing boolean codes default to false
      val booleanAttrs: Map[AttributeName, AttributeBoolean] = (booleanCodes ++ List("RS-G", "RS-FM", "RS-M")).
        filter(!existingKeyNames.contains(_)).
        flatMap { code =>
          Map(AttributeName.withDefaultNS(code) -> AttributeBoolean(false))
        }.toMap

      // Missing list codes default to empty lists
      val listAttrs = listCodes.
        filter(!existingKeyNames.contains(_)).
        flatMap { code =>
          Map(AttributeName.withDefaultNS(code) -> AttributeValueList(Seq.empty))
        }.toMap

      val allAttrs = existingAttrs ++ booleanAttrs ++ listAttrs

      Map(structuredUseRestrictionAttributeName -> AttributeValueRawJson.apply(allAttrs.toJson.compactPrint))
    }

  }

}
