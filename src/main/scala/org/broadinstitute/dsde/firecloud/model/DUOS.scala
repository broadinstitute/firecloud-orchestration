package org.broadinstitute.dsde.firecloud.model

import spray.json._
import spray.json.{JsObject, JsValue}

object DUOS {

  case class DuosDataUse(
    generalUse: Option[Boolean] = None,
    hmbResearch: Option[Boolean] = None,
    diseaseRestrictions: Option[Seq[String]] = None,
    populationOriginsAncestry: Option[Boolean] = None,
    populationStructure: Option[Boolean] = None,
    commercialUse: Option[Boolean] = None,
    methodsResearch: Option[Boolean] = None,
    aggregateResearch: Option[String] = None,
    controlSetOption: Option[String] = None,
    gender: Option[String] = None,
    pediatric: Option[Boolean] = None,
    populationRestrictions: Option[Seq[String]] = None,
    dateRestriction: Option[String] = None,
    recontactingDataSubjects: Option[Boolean] = None,
    recontactMay: Option[String] = None,
    recontactMust: Option[String] = None,
    genomicPhenotypicData: Option[String] = None,
    otherRestrictions: Option[Boolean] = None,
    cloudStorage: Option[String] = None,
    ethicsApprovalRequired: Option[Boolean] = None,
    geographicalRestrictions: Option[String] = None,
    other: Option[String] = None,
    illegalBehavior: Option[Boolean] = None,
    addiction: Option[Boolean] = None,
    sexualDiseases: Option[Boolean] = None,
    stigmatizeDiseases: Option[Boolean] = None,
    vulnerablePopulations: Option[Boolean] = None,
    psychologicalTraits: Option[Boolean] = None,
    nonBiomedical: Option[Boolean] = None
  )

  object DuosDataUse {
    def apply(jsValues: Map[String, JsValue]): DuosDataUse = {
      def getBoolean(f: String): Option[Boolean] = {
        jsValues.get(f) match {
          case Some(b: JsBoolean) => Some(b.value)
          case _ => None
        }
      }
      def getSeqString(f: String): Option[Seq[String]] = {
        jsValues.get(f) match {
          case Some(l: JsArray) => Some(l.elements.collect { case s: JsString => s.value })
          case _ => None
        }
      }
      def getString(f: String): Option[String] = {
        jsValues.get(f) match {
          case Some(s: JsString) => Some(s.value)
          case _ => None
        }
      }
      new DuosDataUse(
        generalUse = getBoolean("generalUse"),
        hmbResearch = getBoolean("hmbResearch"),
        diseaseRestrictions = getSeqString("diseaseRestrictions"),
        populationOriginsAncestry = getBoolean("populationOriginsAncestry"),
        populationStructure = getBoolean("populationStructure"),
        commercialUse = getBoolean("commercialUse"),
        methodsResearch = getBoolean("methodsResearch"),
        aggregateResearch = getString("aggregateResearch"),
        controlSetOption = getString("controlSetOption"),
        gender = getString("gender"),
        pediatric = getBoolean("pediatric"),
        populationRestrictions = getSeqString("populationRestrictions"),
        dateRestriction = getString("dateRestriction"),
        recontactingDataSubjects = getBoolean("recontactingDataSubjects"),
        recontactMay = getString("recontactMay"),
        recontactMust = getString("recontactMust"),
        genomicPhenotypicData = getString("genomicPhenotypicData"),
        otherRestrictions = getBoolean("otherRestrictions"),
        cloudStorage = getString("cloudStorage"),
        ethicsApprovalRequired = getBoolean("ethicsApprovalRequired"),
        geographicalRestrictions = getString("geographicalRestrictions"),
        other = getString("other"),
        illegalBehavior = getBoolean("illegalBehavior"),
        addiction = getBoolean("addiction"),
        sexualDiseases = getBoolean("sexualDiseases"),
        stigmatizeDiseases = getBoolean("stigmatizeDiseases"),
        vulnerablePopulations = getBoolean("vulnerablePopulations"),
        psychologicalTraits = getBoolean("psychologicalTraits"),
        nonBiomedical = getBoolean("nonBiomedical")
      )
    }
  }

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
    translatedUseRestriction: Option[String] = None,
    dataUse: Option[DuosDataUse] = None
  )

  case class ConsentError(
    message: String,
    code: Int
  )

  case class ConsentStatus(
    ok: Option[Boolean],
    degraded: Option[Boolean],
    systems: Option[Map[String, DropwizardHealth]]
  )

}
