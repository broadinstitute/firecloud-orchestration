package org.broadinstitute.dsde.firecloud.model

import scala.language.postfixOps

case class FireCloudKeyValue(
  key: Option[String] = None,
  value: Option[String] = None)

case class ThurloeKeyValue(
  userId: Option[String] = None,
  keyValuePair: Option[FireCloudKeyValue] = None)

case class ProfileWrapper(userId: String, keyValuePairs: List[FireCloudKeyValue])

case class Profile (
    name: String,
    email: String,
    institution: String,
    pi: String,
    linkedNihUsername: Option[String] = None,
    lastLinkTime: Option[Long] = None,
    isDbgapAuthorized: Option[Boolean] = None
  ) extends mappedPropVals {

  require(ProfileValidator.nonEmpty(name), "name must be non-empty")
  require(ProfileValidator.nonEmpty(email), "email must be non-empty")
  require(ProfileValidator.nonEmpty(institution), "institution must be non-empty")
  require(ProfileValidator.nonEmpty(pi), "primary investigator must be non-empty")
}

object Profile {
  def apply(wrapper: ProfileWrapper) = {

    val mappedKVPs:Map[String,String] = (wrapper.keyValuePairs map {
      fckv:FireCloudKeyValue => (fckv.key.get -> fckv.value.get) }).toMap

    new Profile(
      name = mappedKVPs.get("name").get,
      email = mappedKVPs.get("email").get,
      institution = mappedKVPs.get("institution").get,
      pi = mappedKVPs.get("pi").get,
      linkedNihUsername = mappedKVPs.get("linkedNihUsername"),
      lastLinkTime = mappedKVPs.get("lastLinkTime") match {
        case Some(time) => Some(time.toLong)
        case _ => None
      },
      isDbgapAuthorized = mappedKVPs.get("isDbgapAuthorized") match {
        case Some(auth) => Some(auth.toBoolean)
        case _ => None
      }
    )
  }
}

case class NIHLink (linkedNihUsername: String, lastLinkTime: Long, isDbgapAuthorized: Boolean) extends mappedPropVals {
  require(ProfileValidator.nonEmpty(linkedNihUsername), "linkedNihUsername must be non-empty")
}

case class NIHStatus(
    loginRequired: Boolean,
    linkedNihUsername: Option[String] = None,
    isDbgapAuthorized: Option[Boolean] = None,
    lastLinkTime: Option[Long] = None,
    secondsSinceLastLink: Option[Long] = None,
    descriptionSinceLastLink: Option[String] = None
)

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
