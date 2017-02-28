package org.broadinstitute.dsde.firecloud.model


object Ontology {

  case class TermResource(
    id: String,
    ontology: String,
    usable: Boolean,
    label: String,
    definition: String,
    synonyms: Option[List[String]],
    parents: Option[List[TermParent]]
  )

  case class TermParent(
    id: String,
    order: Int,
    label: String,
    definition: String,
    synonyms: Option[List[String]]
  )
}
