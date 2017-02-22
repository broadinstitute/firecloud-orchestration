package org.broadinstitute.dsde.firecloud.model


object Ontology {

  case class SearchResponse(
    id: String,
    label: String,
    definition: String,
    synonyms: List[String]
  )
}
