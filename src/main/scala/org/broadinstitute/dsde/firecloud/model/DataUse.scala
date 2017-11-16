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

}
