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

  case class ConsentError(
    message: String,
    code: Int
  )

}
