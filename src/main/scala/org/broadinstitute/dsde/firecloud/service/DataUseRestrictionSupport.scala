package org.broadinstitute.dsde.firecloud.service

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.DataUse
import org.broadinstitute.dsde.firecloud.model.DataUse.DiseaseOntologyNodeId
import org.broadinstitute.dsde.firecloud.model.Ontology.{TermParent, TermResource}
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.AttributeNameFormat
import org.broadinstitute.dsde.rawls.model._
import spray.http.Uri
import spray.json.DefaultJsonProtocol._
import spray.json._

trait DataUseRestrictionSupport extends LazyLogging {

  private val booleanCodes: Seq[String] = Seq("GRU", "HMB", "NCU", "NPU", "NDMS", "NAGR", "NCTRL", "RS-PD")
  private val listCodes: Seq[String] = Seq("DS", "RS-POP")
  val durFieldNames: Seq[String] = booleanCodes ++ listCodes ++ Seq("RS-G")

  val structuredUseRestrictionAttributeName: AttributeName = AttributeName.withLibraryNS("structuredUseRestriction")
  val dataUseDisplayAttributeName: AttributeName = AttributeName.withLibraryNS("dataUseDisplay")

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
    val allAttrs = makeAttributes(workspace)
    if (allAttrs.nonEmpty) {
      Map(structuredUseRestrictionAttributeName -> AttributeValueRawJson.apply(allAttrs.toJson.compactPrint))
    } else {
      Map.empty[AttributeName, Attribute]
    }
  }

  /**
    * Create a display-friendly version of the structured data use restriction in the form of a
    * list of code strings.
    *
    * @param workspace The Workspace
    * @return An Attribute Map representing a data use display
    */
  def generateUseRestrictionDisplay(workspace: Workspace, termCache: Map[String, TermResource]): Map[AttributeName, Attribute] = {
    makeAttributes(workspace).flatMap {
      case (attr: AttributeName, value: AttributeBoolean) if value.value =>  Seq(attr.name)
      case (attr: AttributeName, value: AttributeBoolean) =>  Seq.empty[String]
      case (attr: AttributeName, value: AttributeValueList) => value.list.map {
        case avl@(a: AttributeString) =>
          termCache.get(a.value) match {
            case None =>
              logger.warn(s"Missing ontology term (workspace-id: ${workspace.workspaceId}, attribute name: ${attr.name}, attribute value: ${a.value})")
              ""
            case Some(o) => "DS:" + o.label
          }
        case avl@(a: AttributeNumber) =>
          val nodeId = DiseaseOntologyNodeId(DataUse.doid_prefix + a.value.intValue().toString)
          termCache.get(nodeId.uri.toString()) match {
            case None =>
              logger.warn(s"Missing ontology term (workspace-id: ${workspace.workspaceId}, attribute name: ${attr.name}, attribute value: ${nodeId.toString})")
              ""
            case Some(o) => "DS:" + o.label
          }
        case a =>
          logger.warn(s"Attribute value list is of the wrong type (workspace-id: ${workspace.workspaceId}, attribute name: ${attr.name}, value type: ${a.getClass})")
          ""
      }
    }.toSeq.filter(_.nonEmpty) match {
      case x if x.nonEmpty => Map(dataUseDisplayAttributeName -> AttributeValueList(x.map(AttributeString)))
      case _ => Map.empty[AttributeName, Attribute]
    }
  }

  private def makeAttributes(workspace: Workspace): Map[AttributeName, Attribute] = {
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

      existingAttrs ++ booleanAttrs ++ listAttrs
    }

  }

}
