package org.broadinstitute.dsde.firecloud.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.OntologyDAO
import org.broadinstitute.dsde.firecloud.model.DUOS.DuosDataUse
import org.broadinstitute.dsde.firecloud.model.DataUse.DiseaseOntologyNodeId
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.AttributeNameFormat
import org.broadinstitute.dsde.rawls.model._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.util.Try

trait DataUseRestrictionSupport extends LazyLogging {

  private val booleanCodes: Seq[String] = Seq("GRU", "HMB", "NCU", "NPU", "NMDS", "NAGR", "NCTRL", "RS-PD", "IRB")
  private val genderCodes: Seq[String] = Seq("RS-G", "RS-FM", "RS-M")
  private val duRestrictionFieldNames: Seq[String] = booleanCodes ++ genderCodes ++ Seq("DS_URL", "RS-POP")
  val allDurFieldNames: Seq[String] = duRestrictionFieldNames ++ Seq("DS")

  private val diseaseLabelsAttributeName: AttributeName = AttributeName.withLibraryNS("DS")
  val structuredUseRestrictionAttributeName: AttributeName = AttributeName.withLibraryNS("structuredUseRestriction")
  val consentCodesAttributeName: AttributeName = AttributeName.withLibraryNS("consentCodes")

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
  def generateStructuredUseRestrictionAttribute(workspace: Workspace): Map[AttributeName, Attribute] = {
    implicit val impAttributeFormat: AttributeFormat with PlainArrayAttributeListSerializer =
      new AttributeFormat with PlainArrayAttributeListSerializer

    getDataUseAttributes(workspace) match {
      case x if x.isEmpty => Map.empty[AttributeName, Attribute]
      case existingAttrs =>

        val existingKeyNames = existingAttrs.keys.map(_.name).toSeq

        // Missing boolean codes default to false
        val booleanAttrs: Map[AttributeName, Attribute] = ((booleanCodes ++ genderCodes) diff existingKeyNames).map { code =>
          AttributeName.withDefaultNS(code) -> AttributeBoolean(false)
        }.toMap

        // Missing list codes default to empty lists
        val listAttrs: Map[AttributeName, Attribute] = (Seq("DS", "RS-POP") diff existingKeyNames).map { code =>
          AttributeName.withDefaultNS(code) -> AttributeValueList(Seq.empty)
        }.toMap

        val allAttrs = existingAttrs ++ booleanAttrs ++ listAttrs
        Map(structuredUseRestrictionAttributeName -> AttributeValueRawJson.apply(allAttrs.toJson.compactPrint))
    }

  }

  /**
    * Looks at a DuosDataUse object (likely previously retrieved from ORSP) and translates it into a
    * set of structured Data Use Restriction attributes.
    *
    * @param duosDataUse
    * @return
    */
  def generateStructuredUseRestrictionAttribute(duosDataUse: DuosDataUse, ontologyDAO: OntologyDAO): Map[AttributeName, Attribute] = {
    // these are straightforward mappings
    val gru = duosDataUse.generalUse.map ( AttributeName.withLibraryNS("GRU") -> AttributeBoolean(_) )
    val hmb = duosDataUse.hmbResearch.map ( AttributeName.withLibraryNS("HMB") -> AttributeBoolean(_) )
    val rspd = duosDataUse.vulnerablePopulations.map ( AttributeName.withLibraryNS("RS-PD") -> AttributeBoolean(_) )
    val ncu = duosDataUse.commercialUse.map ( AttributeName.withLibraryNS("NCU") -> AttributeBoolean(_) )
    val nmds = duosDataUse.methodsResearch.map ( AttributeName.withLibraryNS("NMDS") -> AttributeBoolean(_) )

    // FC supports both NCU and NPU; DUOS only supports one (NCU)
    // val npu = duosDataUse.commercialUse.map ( AttributeName.withLibraryNS("NPU") -> AttributeBoolean(_) )

    // DUOS represents NAGR and NCTRL as strings ("yes"|"no")
    val nagr = duosDataUse.aggregateResearch match {
      case Some(s) if "yes".equals(s.toLowerCase) => Some(AttributeName.withLibraryNS("NAGR") -> AttributeBoolean(true))
      case Some(s) if "no".equals(s.toLowerCase) => Some(AttributeName.withLibraryNS("NAGR") -> AttributeBoolean(false))
      case _ => None
    }
    val nctrl = duosDataUse.controlSetOption match {
      case Some(s) if "yes".equals(s.toLowerCase) => Some(AttributeName.withLibraryNS("NCTRL") -> AttributeBoolean(true))
      case Some(s) if "no".equals(s.toLowerCase) => Some(AttributeName.withLibraryNS("NCTRL") -> AttributeBoolean(false))
      case _ => None
    }

    // DUOS has no concept of "IRB review required"
    // val irb = None

    val ds = duosDataUse.diseaseRestrictions match {
      case Some(Seq()) => Map.empty[AttributeName,Attribute]
      case Some(nodeList) =>
        Map(
          AttributeName.withLibraryNS("DS_URL") -> AttributeValueList(nodeList.map( AttributeString )),
          AttributeName.withLibraryNS("DS") -> AttributeValueList(nodeList.map{ nodeid =>
            ontologyDAO.search(nodeid) match {
              case termResource :: Nil => AttributeString(termResource.label)
              case _ => AttributeString(nodeid)
            }})
        )
      case _ => Map.empty[AttributeName,Attribute]
    }

    val result = Map.empty[AttributeName, Attribute] ++ gru ++ hmb ++ ncu ++ nmds ++ rspd ++ nagr ++ nctrl ++ ds // ++ npu ++ irb

    logger.debug("inbound DuosDataUse: " + duosDataUse)
    logger.debug("outbound attrs: " + result)

    result
  }

  /**
    * Create a display-friendly version of the structured data use restriction in the form of a
    * list of code strings.
    *
    * @param workspace The Workspace
    * @return An Attribute Map representing a data use display
    */
  def generateUseRestrictionDisplayAttribute(workspace: Workspace): Map[AttributeName, Attribute] = {

    val booleanCodes:Seq[String] = getDataUseAttributes(workspace).collect {
      case (attr: AttributeName, AttributeBoolean(true)) => attr.name
    }.toSeq

    val dsLabels:Seq[String] = (workspace.attributes.get(diseaseLabelsAttributeName) collect {
      case value: AttributeValueList => value.list.collect {
        case a: AttributeString => "DS:" + a.value
      }
    }).getOrElse(Seq.empty[String])

    val displayCodes = booleanCodes ++ dsLabels

    if (displayCodes.nonEmpty)
      Map(consentCodesAttributeName -> AttributeValueList(displayCodes.map(AttributeString)))
    else
      Map.empty[AttributeName, Attribute]
  }

  def replaceDataUseAttributes(existing: AttributeMap, preferred: AttributeMap): AttributeMap = {
    // delete pre-existing DU codes, then add the DU codes from ORSP
    (existing -
      structuredUseRestrictionAttributeName -
      consentCodesAttributeName --
      allDurFieldNames.map(x => AttributeName.withLibraryNS(x))) ++ preferred
  }

  // TODO: namespaces on allDurFieldNames??!?!?
  private def getDataUseAttributes(workspace: Workspace): Map[AttributeName, Attribute] = {

    // Find all library attributes that contribute to data use restrictions
    val dataUseAttributes = workspace.attributes.filter { case (attr, value) => duRestrictionFieldNames.contains(attr.name) }

    if (dataUseAttributes.isEmpty) {
      Map.empty[AttributeName, Attribute]
    } else {
      dataUseAttributes.flatMap {
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
            case _ =>
              logger.warn(s"Invalid data use attribute formatted as a string (workspace-id: ${workspace.workspaceId}, attribute name: ${attr.name}, attribute value: ${value.value})")
              Map.empty[AttributeName, Attribute]
          }
        // Handle only what is correctly tagged and ignore anything improperly tagged
        case (attr: AttributeName, value: AttributeBoolean) => Map(AttributeName.withDefaultNS(attr.name) -> value)
        // Turn DS string ids into numeric IDs for ES indexing
        // Also, in this case, we are generating a "DS" attribute to index, not a "DS_URL" attribute
        case (attr: AttributeName, value: AttributeValueList) if attr.name.equals("DS_URL") =>
          val diseaseNumericIdValues = value.list.collect {
            case a: AttributeString => Try(DiseaseOntologyNodeId(a.value)).toOption.map(_.numericId)
          }.flatten
          if (diseaseNumericIdValues.nonEmpty)
            Map(AttributeName.withDefaultNS("DS") -> AttributeValueList(diseaseNumericIdValues.map { n => AttributeNumber(n) }))
          else
            Map.empty[AttributeName, Attribute]
        case (attr: AttributeName, value: AttributeValueList) if attr.name.equals("RS-POP") => Map(AttributeName.withDefaultNS(attr.name) -> value)
        case unmatched =>
          logger.warn(s"Unexpected library data use attribute type: (workspace-id: ${workspace.workspaceId}, attribute name: ${unmatched._1.name}, attribute value: ${unmatched._2.toString})")
          Map.empty[AttributeName, Attribute]
      }
    }

  }

}
