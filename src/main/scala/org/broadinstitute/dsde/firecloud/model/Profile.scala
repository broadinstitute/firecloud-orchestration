package org.broadinstitute.dsde.firecloud.model

import scala.language.postfixOps
import java.util.Date

case class FireCloudKeyValue(
  key: Option[String] = None,
  value: Option[String] = None)

case class ThurloeKeyValue(
  userId: Option[String] = None,
  keyValuePair: Option[FireCloudKeyValue] = None)

case class Profile (name: String, email: String, institution: String, pi: String) extends mappedPropVals {

  require(ProfileValidator.nonEmpty(name), "name must be non-empty")
  require(ProfileValidator.nonEmpty(email), "email must be non-empty")
  require(ProfileValidator.nonEmpty(institution), "institution must be non-empty")
  require(ProfileValidator.nonEmpty(pi), "primary investigator must be non-empty")
}

case class NIHLink (linkedNihUsername: String, lastLinkTime: Long, isDbgapAuthorized: Boolean) extends mappedPropVals {
  require(ProfileValidator.nonEmpty(linkedNihUsername), "linkedNihUsername must be non-empty")
}

object ProfileValidator {
  def nonEmpty(field: String): Boolean = !field.trim.isEmpty
}

trait mappedPropVals {
  def propertyValueMap: Map[String, String] = {
    this.getClass.getDeclaredFields map {
      f =>
        f.setAccessible(true)
        f.getName -> f.get(this).toString
    } toMap
  }
}
