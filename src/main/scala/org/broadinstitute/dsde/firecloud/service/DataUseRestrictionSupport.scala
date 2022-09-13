package org.broadinstitute.dsde.firecloud.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException}
import org.broadinstitute.dsde.firecloud.dataaccess.OntologyDAO
import org.broadinstitute.dsde.firecloud.model.{ConsentCodes}
import org.broadinstitute.dsde.firecloud.model.DUOS.DuosDataUse
import org.broadinstitute.dsde.firecloud.model.DataUse._
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.AttributeNameFormat
import org.broadinstitute.dsde.rawls.model._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.util.Try

case class UseRestriction(structured:  Map[AttributeName, Attribute], display: Map[AttributeName, Attribute])

trait DataUseRestrictionSupport extends LazyLogging {

  private val booleanCodes: Seq[String] = Seq("GRU", "HMB", "NCU", "NPU", "NMDS", "NAGR", "NCTRL", "RS-PD", "IRB")
  private val genderCodes: Seq[String] = Seq("RS-G", "RS-FM", "RS-M")
  private val duRestrictionFieldNames: Seq[String] = booleanCodes ++ genderCodes ++ Seq("DS_URL", "RS-POP")
  val allDurFieldNames: Seq[String] = duRestrictionFieldNames ++ Seq("DS")

  private val diseaseLabelsAttributeName: AttributeName = AttributeName.withLibraryNS("DS")
  val structuredUseRestrictionName = "structuredUseRestriction"
  val structuredUseRestrictionAttributeName: AttributeName = AttributeName.withLibraryNS(structuredUseRestrictionName)
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
    * @param attributes The Attributes
    * @return A structured data use restriction Attribute Map
    */
  def transformStructuredUseRestrictionAttribute(attributes: Map[AttributeName, Attribute]): Map[AttributeName, Attribute] = {
    implicit val impAttributeFormat: AttributeFormat with PlainArrayAttributeListSerializer =
      new AttributeFormat with PlainArrayAttributeListSerializer

    attributes match {
      case x if x.isEmpty => Map.empty[AttributeName, Attribute]
      case existingAttrs =>

        val existingKeyNames = existingAttrs.keys.map(_.name).toSeq

        // Missing boolean codes default to false
        val booleanAttrs: Map[AttributeName, Attribute] = ((ConsentCodes.booleanCodes ++ ConsentCodes.genderCodes) diff existingKeyNames).map { code =>
          AttributeName.withDefaultNS(code) -> AttributeBoolean(false)
        }.toMap

        // Missing list codes default to empty lists
        val listAttrs: Map[AttributeName, Attribute] = (Seq(ConsentCodes.DS) diff existingKeyNames).map { code =>
          AttributeName.withDefaultNS(code) -> AttributeValueList(Seq.empty)
        }.toMap

        val allAttrs = existingAttrs ++ booleanAttrs ++ listAttrs
        Map(structuredUseRestrictionAttributeName -> AttributeValueRawJson.apply(allAttrs.toJson.compactPrint))
    }
  }

  def generateStructuredAndDisplayAttributes(workspace: WorkspaceDetails, ontologyDAO: OntologyDAO): UseRestriction = {
    getDataUseAttributes(workspace)  match {
      case None => UseRestriction(Map.empty[AttributeName, Attribute],Map.empty[AttributeName, Attribute])
      case Some(request) => {
        val consentMap = generateUseRestrictionBooleanMap(request)
        val structuredAttribute =  if (workspace.attributes.getOrElse(Map.empty).isEmpty) Map.empty[AttributeName, Attribute] else transformStructuredUseRestrictionAttribute(consentMap ++ generateUseRestrictionDSStructuredMap(request))
        val displayAttribute = transformUseRestrictionDisplayAttribute(consentMap ++ generateUseRestrictionDSDisplayMap(request), ontologyDAO)
        UseRestriction(structured = structuredAttribute, display = displayAttribute)
      }
    }
  }


  def generateStructuredUseRestrictionAttribute(request: StructuredDataRequest, ontologyDAO: OntologyDAO): Map[String, JsValue] = {
    generateStructuredDataResponse(request, ontologyDAO).formatWithPrefix()
  }

  def generateStructuredDataResponse(request: StructuredDataRequest, ontologyDAO: OntologyDAO): StructuredDataResponse = {
    val diseaseCodesArray = getDiseaseNames(request.diseaseUseRequired, ontologyDAO)
    val booleanConsentMap = generateUseRestrictionBooleanMap(request)
    val diseaseSpecificMap = generateUseRestrictionDSStructuredMap(request)
    // convert to array of consent codes
    val consentCodes = booleanConsentMap.filter(_._2.value).map(_._1.name).toArray ++ diseaseCodesArray

    StructuredDataResponse(consentCodes, FireCloudConfig.Duos.dulvn, request.prefix.getOrElse(""), booleanConsentMap ++ diseaseSpecificMap)
  }


  def generateUseRestrictionBooleanMap(request: StructuredDataRequest): Map[AttributeName, AttributeBoolean] = {
    Map(
      AttributeName.withDefaultNS(ConsentCodes.GRU) -> AttributeBoolean(request.generalResearchUse),
      AttributeName.withDefaultNS(ConsentCodes.HMB) -> AttributeBoolean(request.healthMedicalBiomedicalUseRequired),
      AttributeName.withDefaultNS(ConsentCodes.NCU) -> AttributeBoolean(request.commercialUseProhibited),
      AttributeName.withDefaultNS(ConsentCodes.NPU) -> AttributeBoolean(request.forProfitUseProhibited),
      AttributeName.withDefaultNS(ConsentCodes.NMDS) -> AttributeBoolean(request.methodsResearchProhibited),
      AttributeName.withDefaultNS(ConsentCodes.NAGR) -> AttributeBoolean(request.aggregateLevelDataProhibited),
      AttributeName.withDefaultNS(ConsentCodes.NCTRL) -> AttributeBoolean(request.controlsUseProhibited),
      AttributeName.withDefaultNS(ConsentCodes.RSPD) -> AttributeBoolean(request.pediatricResearchRequired),
      AttributeName.withDefaultNS(ConsentCodes.IRB) -> AttributeBoolean(request.irbRequired)) ++ getGenderCodeMap(request.genderUseRequired)
  }

  def generateUseRestrictionDSStructuredMap(request: StructuredDataRequest): Map[AttributeName, Attribute] = {
    Map(AttributeName.withDefaultNS(ConsentCodes.DS) -> AttributeValueList(request.diseaseUseRequired.toIndexedSeq.map(DiseaseOntologyNodeId(_).numericId).map(AttributeNumber(_))))
  }

  def generateUseRestrictionDSDisplayMap(request: StructuredDataRequest): Map[AttributeName, Attribute] = {
    Map(AttributeName.withDefaultNS(ConsentCodes.DSURL) -> AttributeValueList(request.diseaseUseRequired.toIndexedSeq.map(AttributeString(_))))
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
    val gru = duosDataUse.generalUse.map ( AttributeName.withLibraryNS(ConsentCodes.GRU) -> AttributeBoolean(_) )
    val hmb = duosDataUse.hmbResearch.map ( AttributeName.withLibraryNS(ConsentCodes.HMB) -> AttributeBoolean(_) )
    val rspd = duosDataUse.pediatric.map ( AttributeName.withLibraryNS(ConsentCodes.RSPD) -> AttributeBoolean(_) )
    val ncu = duosDataUse.commercialUse.map ( AttributeName.withLibraryNS(ConsentCodes.NCU) -> AttributeBoolean(_) )
    val nmds = duosDataUse.methodsResearch.map ( AttributeName.withLibraryNS(ConsentCodes.NMDS) -> AttributeBoolean(_) )

    // FC supports both NCU and NPU; DUOS only supports one (NCU)
    // val npu = duosDataUse.commercialUse.map ( AttributeName.withLibraryNS("NPU") -> AttributeBoolean(_) )

    // DUOS represents NAGR and NCTRL as strings ("yes"|"no")
    val nagr = duosDataUse.aggregateResearch match {
      case Some(s) if "yes".equals(s.toLowerCase) => Some(AttributeName.withLibraryNS(ConsentCodes.NAGR) -> AttributeBoolean(true))
      case Some(s) if "no".equals(s.toLowerCase) => Some(AttributeName.withLibraryNS(ConsentCodes.NAGR) -> AttributeBoolean(false))
      case _ => None
    }
    val nctrl = duosDataUse.controlSetOption match {
      case Some(s) if "yes".equals(s.toLowerCase) => Some(AttributeName.withLibraryNS(ConsentCodes.NCTRL) -> AttributeBoolean(true))
      case Some(s) if "no".equals(s.toLowerCase) => Some(AttributeName.withLibraryNS(ConsentCodes.NCTRL) -> AttributeBoolean(false))
      case _ => None
    }

    // DUOS has no concept of "IRB review required"
    // val irb = None

    // disease restrictions: DS, DS_URL
    val ds = duosDataUse.diseaseRestrictions match {
      case Some(Seq()) => Map.empty[AttributeName,Attribute]
      case Some(nodeList) =>
        Map(
          AttributeName.withLibraryNS(ConsentCodes.DSURL) -> AttributeValueList(nodeList.map( AttributeString )),
          AttributeName.withLibraryNS(ConsentCodes.DS) -> AttributeValueList(nodeList.map{ nodeid =>
            ontologyDAO.search(nodeid) match {
              case termResource :: Nil => AttributeString(termResource.label)
              case _ => AttributeString(nodeid)
            }})
        )
      case _ => Map.empty[AttributeName,Attribute]
    }

    // gender restrictions: RS-G, RS-FM, RS-M
    val rsg = duosDataUse.gender match {
      case Some(f:String) if "female".equals(f.toLowerCase) => Map(
        AttributeName.withLibraryNS("RS-G") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("RS-FM") -> AttributeBoolean(true)
      )
      case Some(m:String) if "male".equals(m.toLowerCase) => Map(
        AttributeName.withLibraryNS("RS-G") -> AttributeBoolean(true),
        AttributeName.withLibraryNS("RS-M") -> AttributeBoolean(true)
      )
      case _ => Map.empty[AttributeName,Attribute]
    }

    val result = Map.empty[AttributeName, Attribute] ++ gru ++ hmb ++ ncu ++ nmds ++
      rspd ++ nagr ++ nctrl ++ ds ++ rsg // ++ npu ++ irb

    logger.debug("inbound DuosDataUse: " + duosDataUse)
    logger.debug("outbound attrs: " + result)

    result
  }

  /**
    * Create a display-friendly version of the structured data use restriction in the form of a
    * list of code strings.
    *
    * @param attributes The Attributes
    * @return An Attribute Map representing a data use display
    */
  def transformUseRestrictionDisplayAttribute(attributes: Map[AttributeName, Attribute], ontologyDAO: OntologyDAO): Map[AttributeName, Attribute] = {

    val booleanCodes:Seq[String] = attributes.collect {
      case (attr: AttributeName, AttributeBoolean(true)) => attr.name
    }.toSeq


    val dsLabels:Seq[String] = (attributes.get(AttributeName.withDefaultNS(ConsentCodes.DSURL)) collect {
      case value: AttributeValueList => value.list.collect {
        case a: AttributeString => a.value
      }
    }).getOrElse(Seq.empty[String])

    val diseaseDisplayNames = getDiseaseNames(dsLabels.toArray, ontologyDAO)

    val displayCodes = booleanCodes ++ diseaseDisplayNames

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
      ConsentCodes.allPreviousDurFieldNames.map(AttributeName.withLibraryNS)) ++ preferred
  }

  private def getDiseaseNames(diseaseCodes: Array[String], ontologyDAO: OntologyDAO): Array[String] = {
   diseaseCodes.map { nodeid =>
      ontologyDAO.search(nodeid) match {
        case termResource :: Nil => ConsentCodes.DS + ":" + termResource.label
        case _ =>  throw new FireCloudException(s"DS code $nodeid did not match any diseases.")
      }
    }
  }

  private def getGenderCodeMap(rsg: String): Map[AttributeName, AttributeBoolean] = {
    rsg.toLowerCase match {
      case "female" =>
        Map(AttributeName.withDefaultNS(ConsentCodes.RSG) -> AttributeBoolean(true),
          AttributeName.withDefaultNS(ConsentCodes.RSFM) -> AttributeBoolean(true),
          AttributeName.withDefaultNS(ConsentCodes.RSM) -> AttributeBoolean(false))
      case "male" =>
        Map(AttributeName.withDefaultNS(ConsentCodes.RSG) -> AttributeBoolean(true),
          AttributeName.withDefaultNS(ConsentCodes.RSFM) -> AttributeBoolean(false),
          AttributeName.withDefaultNS(ConsentCodes.RSM) -> AttributeBoolean(true))
      case _ =>
        Map(AttributeName.withDefaultNS(ConsentCodes.RSG) -> AttributeBoolean(false),
          AttributeName.withDefaultNS(ConsentCodes.RSFM) -> AttributeBoolean(false),
          AttributeName.withDefaultNS(ConsentCodes.RSM) -> AttributeBoolean(false))
    }
  }

  // TODO: this method needs to respect attribute namespaces: see GAWB-3173
  private def getDataUseAttributes(workspace: WorkspaceDetails): Option[StructuredDataRequest] = {
    val dataUseAttributes = workspace.attributes.getOrElse(Map.empty).collect {
      case (attr, value) if ConsentCodes.duRestrictionFieldNames.contains(attr.name) => (attr.name, value)
    }

    def getBooleanPayloadValues(consentCode: String): Boolean = {
      dataUseAttributes.get(consentCode) match {
        case Some(att: AttributeBoolean) => att.value
        case _ => false
      }
    }

    def getDiseaseArray: Array[String] = {
      dataUseAttributes.get(ConsentCodes.DSURL) match {
        case Some(attList: AttributeValueList) => {
          attList.list.collect {
            case a: AttributeString => Try(DiseaseOntologyNodeId(a.value)).toOption.map(_.uri.toString)
          }.flatten.toArray
        }
        case _ => Array.empty
      }
    }

    def getGenderString: String = {
      dataUseAttributes.get(ConsentCodes.RSG) match {
        case Some(att: AttributeString) => att.value
        case _ => "None"
      }
    }

    def getNagr: Boolean = {
      dataUseAttributes.get(ConsentCodes.NAGR) match {
        case Some(att: AttributeString) if att.value.toLowerCase == "yes" => true
        case Some(att: AttributeBoolean) => att.value
        case _ => false
      }
    }

    if (dataUseAttributes.isEmpty)
      None
    else
      Some(StructuredDataRequest(
      generalResearchUse = getBooleanPayloadValues(ConsentCodes.GRU),
      healthMedicalBiomedicalUseRequired = getBooleanPayloadValues(ConsentCodes.HMB),
      diseaseUseRequired = getDiseaseArray,
      commercialUseProhibited = getBooleanPayloadValues(ConsentCodes.NCU),
      forProfitUseProhibited = getBooleanPayloadValues(ConsentCodes.NPU),
      methodsResearchProhibited = getBooleanPayloadValues(ConsentCodes.NMDS),
      aggregateLevelDataProhibited = getNagr,
      controlsUseProhibited = getBooleanPayloadValues(ConsentCodes.NCTRL),
      genderUseRequired = getGenderString,
      pediatricResearchRequired = getBooleanPayloadValues(ConsentCodes.RSPD),
      irbRequired = getBooleanPayloadValues(ConsentCodes.IRB),
      prefix = Some(AttributeName.libraryNamespace + AttributeName.delimiter)))
  }

}
