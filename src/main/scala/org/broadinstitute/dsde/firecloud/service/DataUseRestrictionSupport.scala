package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.{Attribute, AttributeListElementable, _}

// TODO: Do we need this at all?
object DataUseRestrictionSupport {
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
}

trait DataUseRestrictionSupport {

  private val booleanCodes = List("GRU", "HMB", "NCU", "NPU", "NDMS", "NAGR", "NCTRL","RS-PD")
  private val listCodes = List("DS", "RS-POP")

  /**
    * This method looks at all of the library attributes that are associated to Consent Codes and
    * builds a Data Use Restriction object from the values. Most are boolean values, some are lists
    * of strings, and in the case of gender, we need to transform a string value to a trio of booleans.
    *
    * @param libraryAttrs Library Attributes
    * @return 'dataUseRestriction' Attribute Map
    */
  def makeDataUseRestriction(libraryAttrs: Map[AttributeName, Attribute]): Map[AttributeName, Attribute] = {
    if (libraryAttrs.isEmpty) {
      Map(AttributeName.withLibraryNS("dataUseRestriction") -> AttributeValueEmptyList[Attribute])
    } else {
      val durAttributes: AttributeMap = libraryAttrs.flatMap {
        case (attr: AttributeName, value: AttributeBoolean) if booleanCodes.contains(attr.name) => Map(AttributeName.withDefaultNS(attr.name) -> value)
        case (attr: AttributeName, value: AttributeList[String]) if listCodes.contains(attr.name) => Map(AttributeName.withDefaultNS(attr.name) -> value)
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
      } ++ (booleanCodes ++ List("RS-G", "RS-FM", "RS-M")).map { code =>
        // Populate missing boolean codes with default false
        val attr = AttributeName.withDefaultNS(code)
        if (!durAttributes.isDefinedAt(attr)) {
          Map(attr -> AttributeBoolean(false))
        }
      }.flatten ++ listCodes.map { code =>
        // Populate missing list codes with default empty lists
        val attr = AttributeName.withDefaultNS(code)
        if (!durAttributes.isDefinedAt(attr)) {
          Map(attr -> AttributeValueList(Seq.empty))
        }
      }.flatten

      // TODO: Figure out how to take this Map(AttributeName -> AttributeMap) and make it a Map(AttributeName -> Attribute)
      Map(AttributeName.withLibraryNS("dataUseRestriction") -> durAttributes)
    }


    Map(AttributeName.withLibraryNS("dataUseRestriction") -> AttributeValueEmptyList[Attribute])
  }

}
