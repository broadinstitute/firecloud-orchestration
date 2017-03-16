package org.broadinstitute.dsde.firecloud.model

import spray.json.JsObject

object DUOS {

  case class Consent(
    consentId: String,
    name: String,
    createDate: Option[Long] = None,
    lastUpdate: Option[Long] = None,
    sortDate: Option[Long] = None,
    requiresManualReview: Option[Boolean] = None,
    dataUseLetter: Option[String] = None,
    useRestriction: Option[JsObject] = None,
    dulName: Option[String] = None,
    translatedUseRestriction: Option[String] = None
  )

}

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
    label: String,
    definition: Option[String] = None,
    synonyms: Option[List[String]] = None
  ) {
    def toESTermParent: ESTermParent =
      ESTermParent(label, order)
  }

  case class ESTermParent(label: String, order: Int)
}
