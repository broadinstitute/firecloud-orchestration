package org.broadinstitute.dsde.firecloud.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.OntologyDAO
import org.broadinstitute.dsde.firecloud.model.DUOS.{DuosDataUse, StructuredDataRequest, StructuredDataResponse}
import org.broadinstitute.dsde.firecloud.model.DataUse.DiseaseOntologyNodeId
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.AttributeNameFormat
import org.broadinstitute.dsde.rawls.model._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.util.Try

object ConsentCodes extends Enumeration {
  val GRU = "GRU"
  val HMB = "HMB"
  val NCU = "NCU"
  val NPU = "NPU"
  val NMDS = "NMDS"
  val NAGR = "NAGR"
  val NCTRL = "NCTRL"
  val RSPD = "RS-PD"
  val IRB = "IRB"
  val RSG = "RS-G"
  val RSFM = "RS-FM"
  val RSM = "RS-M"
  val DSURL = "DS_URL"
  val RSPOP = "RS-POP"
  val DS = "DS"

  val booleanCodes = Seq(GRU, HMB, NCU, NPU, NMDS, NAGR, NCTRL, RSPD, IRB)
  val genderCodes = Seq(RSG, RSFM, RSM)
  val duRestrictionFieldNames = booleanCodes ++ genderCodes ++ Seq(DSURL, RSPOP)
  val allDurFieldNames = duRestrictionFieldNames ++ Seq(DS)
  val diseaseLabelsAttributeName: AttributeName = AttributeName.withLibraryNS(DS)
}

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
    val rspd = duosDataUse.pediatric.map ( AttributeName.withLibraryNS("RS-PD") -> AttributeBoolean(_) )
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

    // disease restrictions: DS, DS_URL
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

    // population restrictions: RS-POP
    val rspop = duosDataUse.populationRestrictions match {
      case Some(Seq()) => Map.empty[AttributeName,Attribute]
      case Some(populations) =>
        Map(
          AttributeName.withLibraryNS("RS-PD") -> AttributeValueList(populations.map(AttributeString))
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
      rspd ++ nagr ++ nctrl ++ ds ++ rspop ++ rsg // ++ npu ++ irb

    logger.debug("inbound DuosDataUse: " + duosDataUse)
    logger.debug("outbound attrs: " + result)

    result
  }


  // Takes in any Product (case classes extend Product) and a prefix and creates a map of all the fields and their values
  // with the prefix prepended to the field
  private def formatWithPrefix(prefix: String, product: Product): Map[String, JsValue] = {
    implicit object AnyJsonFormat extends RootJsonFormat[Any] {
      def write(x: Any) = x match {
        case n: Int => JsNumber(n)
        case s: String => JsString(s)
        case b: Boolean if b == true => JsTrue
        case b: Boolean if b == false => JsFalse
      }
      def read(value: JsValue) = value match {
        case JsNumber(n) => n.intValue()
        case JsString(s) => s
        case JsTrue => true
        case JsFalse => false
      }
    }

    val values = product.productIterator
    product.getClass.getDeclaredFields.map( prefix + _.getName -> values.next.toJson ).toMap
  }

  def generateStructuredUseRestrictionAttribute(request: StructuredDataRequest, ontologyDAO: OntologyDAO): Map[String, JsValue] = {
    // get DS diseases (map over array of ints)
    val diseaseCodesArray = request.diseaseUseOnly.map { nodeid =>
      ontologyDAO.search("http://purl.obolibrary.org/obo/DOID_" + nodeid.toString) match {
        case termResource :: Nil => "DS:" + termResource.label
        case _ =>  "" // return an error if the int does not correlate to a disease?
      }
    }

    val genderCodeMap = request.genderUseOnly match {
      case "female" => Map(ConsentCodes.RSG -> AttributeBoolean(true), ConsentCodes.RSFM -> AttributeBoolean(true), ConsentCodes.RSM -> AttributeBoolean(false))
      case "male" => Map(ConsentCodes.RSG -> AttributeBoolean(true), ConsentCodes.RSFM -> AttributeBoolean(false), ConsentCodes.RSM -> AttributeBoolean(true))
      case "N/A" => Map(ConsentCodes.RSG -> AttributeBoolean(false), ConsentCodes.RSFM -> AttributeBoolean(false), ConsentCodes.RSM -> AttributeBoolean(false))
    }

    // create map of correct restrictions
    val consentMap = Map(
      ConsentCodes.GRU -> AttributeBoolean(request.generalResearchUse),
      ConsentCodes.HMB -> AttributeBoolean(request.healthMedicalUseOnly),
      ConsentCodes.NCU -> AttributeBoolean(request.commercialUseProhibited),
      ConsentCodes.NPU -> AttributeBoolean(request.forProfitUseProhibited),
      ConsentCodes.NMDS -> AttributeBoolean(request.methodsResearchProhibited),
      ConsentCodes.NAGR -> AttributeBoolean(request.aggregateLevelDataProhibited),
      ConsentCodes.NCTRL -> AttributeBoolean(request.controlsUseProhibited),
      ConsentCodes.RSPD -> AttributeBoolean(request.pediatricResearchOnly),
      ConsentCodes.IRB -> AttributeBoolean(request.IRB)) ++ genderCodeMap

    // convert to array of consent codes
    val consentCodes = consentMap.filter(_._2.value).map(_._1).toArray ++ diseaseCodesArray

    // add the dul version
    formatWithPrefix(request.prefix, StructuredDataResponse(consentCodes, 1.0, consentMap ++ Map(ConsentCodes.DS -> AttributeValueList(request.diseaseUseOnly.map(AttributeNumber(_))))))
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
      allDurFieldNames.map(AttributeName.withLibraryNS)) ++ preferred
  }

  // TODO: this method needs to respect attribute namespaces: see GAWB-3173
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
