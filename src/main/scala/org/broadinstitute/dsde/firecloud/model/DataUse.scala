package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.rawls.model.AttributeName
import spray.http.Uri

object DataUse {

  final val doid_prefix = "http://purl.obolibrary.org/obo/DOID_"

  case class ResearchPurpose(
    DS:    Seq[DiseaseOntologyNodeId],
    NMDS:  Boolean,
    NCTRL: Boolean,
    NAGR:  Boolean,
    POA:   Boolean,
    NCU:   Boolean)
  object ResearchPurpose {
    def default = {
      new ResearchPurpose(Seq.empty[DiseaseOntologyNodeId], NMDS=false, NCTRL=false, NAGR=false, POA=false, NCU=false)
    }

    def apply(request: ResearchPurposeRequest): ResearchPurpose = {
      requestToResearchPurpose(request)
    }
  }

  case class DiseaseOntologyNodeId(uri: Uri, numericId: Int)
  object DiseaseOntologyNodeId {
    def apply(stringid:String) = {
      require(stringid.startsWith(doid_prefix), s"Disease Ontology node id must be in the form '${doid_prefix}NNN'")
      val uri = Uri(stringid)
      val numericId = stringid.stripPrefix(doid_prefix).toInt
      new DiseaseOntologyNodeId(uri, numericId)
    }
  }

  case class ResearchPurposeRequest(
    DS:     Option[Seq[String]],
    NMDS:   Option[Boolean],
    NCTRL:  Option[Boolean],
    NAGR:   Option[Boolean],
    POA:    Option[Boolean],
    NCU:    Option[Boolean],
    prefix: Option[String])
  object ResearchPurposeRequest {
    def empty: ResearchPurposeRequest = {
      new ResearchPurposeRequest(DS = None, NMDS = None, NCTRL = None, NAGR = None, POA = None, NCU = None, prefix = None)
    }
  }

  def requestToResearchPurpose(r: ResearchPurposeRequest): ResearchPurpose = {
    ResearchPurpose(
      DS = r.DS match {
        case Some(ds) => ds.map(DiseaseOntologyNodeId(_))
        case None => Seq.empty[DiseaseOntologyNodeId]
      },
      NMDS = r.NMDS.getOrElse(false),
      NCTRL = r.NCTRL.getOrElse(false),
      NAGR = r.NAGR.getOrElse(false),
      POA = r.POA.getOrElse(false),
      NCU = r.NCU.getOrElse(false))
  }
}

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
