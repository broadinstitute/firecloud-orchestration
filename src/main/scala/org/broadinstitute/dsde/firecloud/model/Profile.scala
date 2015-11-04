package org.broadinstitute.dsde.firecloud.model

case class FireCloudKeyValue(
  key: Option[String] = None,
  value: Option[String] = None)

case class ThurloeKeyValue(
  userId: Option[String] = None,
  keyValuePair: Option[FireCloudKeyValue] = None)

case class Profile (name: String, email: String, institution: String, pi: String) {

  require(ProfileValidator.nonEmpty(name), "name must be non-empty")
  require(ProfileValidator.nonEmpty(email), "email must be non-empty")
  require(ProfileValidator.nonEmpty(institution), "institution must be non-empty")
  require(ProfileValidator.nonEmpty(pi), "primary investigator must be non-empty")

  def propertyValueMap: Map[String, String] = {
    this.getClass.getDeclaredFields map {
      f =>
        f.setAccessible(true)
        f.getName -> f.get(this).asInstanceOf[String]
    } toMap
  }

}

object ProfileValidator {
  def nonEmpty(field: String): Boolean = !field.trim.isEmpty
}
