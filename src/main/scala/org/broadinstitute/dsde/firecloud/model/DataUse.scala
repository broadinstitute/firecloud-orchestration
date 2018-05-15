package org.broadinstitute.dsde.firecloud.model

import spray.http.Uri

object DataUse {

  private final val doid_prefix = "http://purl.obolibrary.org/obo/DOID_"

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
