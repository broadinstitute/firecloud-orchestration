package org.broadinstitute.dsde.firecloud.model


object Ontology {

  case class TermResource(
    id: String,
    ontology: String,
    usable: Boolean,
    label: String,
    definition: Option[String] = None,
    synonyms: Option[List[String]] = None,
    parents: Option[List[TermParent]] = None
  )

  case class TermParent(
    id: String,
    order: Int,
    label: Option[String],
    definition: Option[String] = None,
    synonyms: Option[List[String]] = None
  ) {
    def toESTermParent: ESTermParent =
      ESTermParent(label.getOrElse(""), order)
  }

  case class ESTermParent(label: String, order: Int)
}
