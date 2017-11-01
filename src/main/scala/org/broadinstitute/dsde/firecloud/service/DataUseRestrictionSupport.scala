package org.broadinstitute.dsde.firecloud.service

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.OntologyDAO
import org.broadinstitute.dsde.firecloud.model.DataUse.DiseaseOntologyNodeId
import org.broadinstitute.dsde.firecloud.model.LibrarySearchParams
import org.broadinstitute.dsde.firecloud.model.Ontology.{TermParent, TermResource}
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.AttributeNameFormat
import org.broadinstitute.dsde.rawls.model._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.{ExecutionContext, Future}

trait DataUseRestrictionSupport extends LazyLogging {

  private val booleanCodes: Seq[String] = Seq("GRU", "HMB", "NCU", "NPU", "NDMS", "NAGR", "NCTRL", "RS-PD")
  private val listCodes: Seq[String] = Seq("DS", "RS-POP")
  val durFieldNames: Seq[String] = booleanCodes ++ listCodes ++ Seq("RS-G")

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

    val libraryAttrs = workspace.attributes.filter { case (attr, value) => durFieldNames.contains(attr.name) }

    if (libraryAttrs.isEmpty) {
      Map.empty[AttributeName, Attribute]
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
            case _ =>
              logger.warn(s"Invalid data use attribute formatted as a string (workspace-id: ${workspace.workspaceId}, attribute name: ${attr.name}, attribute value: ${value.value})")
              Map.empty[AttributeName, Attribute]
          }
        // Handle only what is correctly tagged and ignore anything improperly tagged
        case (attr: AttributeName, value: AttributeBoolean) => Map(AttributeName.withDefaultNS(attr.name) -> value)
        case (attr: AttributeName, value: AttributeValueList) => Map(AttributeName.withDefaultNS(attr.name) -> value)
        case unmatched =>
          logger.warn(s"Unexpected data use attribute type: (workspace-id: ${workspace.workspaceId}, attribute name: ${unmatched._1.name}, attribute value: ${unmatched._2.toString})")
          Map.empty[AttributeName, Attribute]
      }

      val existingKeyNames = existingAttrs.keys.map(_.name).toSeq

      // Missing boolean codes default to false
      val booleanAttrs: Map[AttributeName, Attribute] = ((booleanCodes ++ List("RS-G", "RS-FM", "RS-M")) diff existingKeyNames).map { code =>
        AttributeName.withDefaultNS(code) -> AttributeBoolean(false)
      }.toMap

      // Missing list codes default to empty lists
      val listAttrs: Map[AttributeName, Attribute] = (listCodes diff existingKeyNames).map { code =>
        AttributeName.withDefaultNS(code) -> AttributeValueList(Seq.empty)
      }.toMap

      val allAttrs = existingAttrs ++ booleanAttrs ++ listAttrs

      Map(structuredUseRestrictionAttributeName -> AttributeValueRawJson.apply(allAttrs.toJson.compactPrint))
    }

  }

  def augmentSearchCriteria(criteria:LibrarySearchParams, ontologyDAO: OntologyDAO)(implicit ec: ExecutionContext): Future[LibrarySearchParams] = {
    if (criteria.researchPurpose.isEmpty || criteria.researchPurpose.get.DS.isEmpty)
      Future.successful(criteria) // return unchanged; no ontology nodes to augment
    else {
      val origPurpose = criteria.researchPurpose.get
      // for all nodes in the research purpose's DS value, query ontology to get their parent nodes
      val targetNodes = origPurpose.DS
      Future.sequence(targetNodes map (node => ontologyDAO.search(node.uri.toString))) map { allTermResults =>
        val parentsToAugment:Seq[DiseaseOntologyNodeId] = (allTermResults collect {
          case Some(terms:List[TermResource]) => terms.head.parents.getOrElse(List.empty[TermParent]).map(parent => DiseaseOntologyNodeId(parent.id))
        }).flatten
        // append the parent node info to the original research purpose
        val newDSValue = targetNodes ++ parentsToAugment
        val newResearchPurpose = origPurpose.copy(DS = newDSValue)
        criteria.copy(researchPurpose = Some(newResearchPurpose))
      }
    }
  }

}
