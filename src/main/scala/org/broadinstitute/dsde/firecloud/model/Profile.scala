package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.utils.DateUtils
import spray.json.DefaultJsonProtocol._

import scala.language.postfixOps

case class FireCloudKeyValue(
  key: Option[String] = None,
  value: Option[String] = None)

case class ThurloeKeyValue(
  userId: Option[String] = None,
  keyValuePair: Option[FireCloudKeyValue] = None)

case class ThurloeKeyValues(
  userId: Option[String] = None,
  keyValuePairs: Option[Seq[FireCloudKeyValue]] = None)

case class ProfileWrapper(userId: String, keyValuePairs: List[FireCloudKeyValue])

case class BasicProfile (
    firstName: String,
    lastName: String,
    title: String,
    contactEmail: Option[String],
    institute: String,
    institutionalProgram: String,
    programLocationCity: String,
    programLocationState: String,
    programLocationCountry: String,
    pi: String,
    nonProfitStatus: String
  ) extends mappedPropVals {
  require(ProfileValidator.nonEmpty(firstName), "first name must be non-empty")
  require(ProfileValidator.nonEmpty(lastName), "last name must be non-empty")
  require(ProfileValidator.nonEmpty(title), "title must be non-empty")
  require(ProfileValidator.emptyOrValidEmail(contactEmail), "contact email must be valid or empty")
  require(ProfileValidator.nonEmpty(institute), "institute must be non-empty")
  require(ProfileValidator.nonEmpty(institutionalProgram), "institutional program must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationCity), "program location city must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationState), "program location state must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationCountry), "program location country must be non-empty")
  require(ProfileValidator.nonEmpty(pi), "principal investigator/program lead must be non-empty")
  require(ProfileValidator.nonEmpty(nonProfitStatus), "non-profit status must be non-empty")
}

case class Profile (
    firstName: String,
    lastName: String,
    title: String,
    contactEmail: Option[String],
    institute: String,
    institutionalProgram: String,
    programLocationCity: String,
    programLocationState: String,
    programLocationCountry: String,
    pi: String,
    nonProfitStatus: String,
    linkedNihUsername: Option[String] = None,
    linkExpireTime: Option[Long] = None,
    isDbgapAuthorized: Option[Boolean] = None
  ) extends mappedPropVals {
  require(ProfileValidator.nonEmpty(firstName), "first name must be non-empty")
  require(ProfileValidator.nonEmpty(lastName), "last name must be non-empty")
  require(ProfileValidator.nonEmpty(title), "title must be non-empty")
  require(ProfileValidator.emptyOrValidEmail(contactEmail), "contact email must be valid or empty")
  require(ProfileValidator.nonEmpty(institute), "institute must be non-empty")
  require(ProfileValidator.nonEmpty(institutionalProgram), "institutional program must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationCity), "program location city must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationState), "program location state must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationCountry), "program location country must be non-empty")
  require(ProfileValidator.nonEmpty(pi), "principal investigator/program lead must be non-empty")
  require(ProfileValidator.nonEmpty(nonProfitStatus), "non-profit status must be non-empty")
}

object Profile {

  // increment this number every time you make a change to the user-provided profile fields
  val currentVersion:Int = 3

  def apply(wrapper: ProfileWrapper) = {

    val mappedKVPs:Map[String,String] = (wrapper.keyValuePairs map {
      fckv:FireCloudKeyValue => (fckv.key.get -> fckv.value.get) }).toMap

    new Profile(
      firstName = mappedKVPs.get("firstName").get,
      lastName = mappedKVPs.get("lastName").get,
      title = mappedKVPs.get("title").get,
      contactEmail = mappedKVPs.get("contactEmail"),
      institute = mappedKVPs.get("institute").get,
      institutionalProgram = mappedKVPs.get("institutionalProgram").get,
      programLocationCity = mappedKVPs.get("programLocationCity").get,
      programLocationState = mappedKVPs.get("programLocationState").get,
      programLocationCountry = mappedKVPs.get("programLocationCountry").get,
      pi = mappedKVPs.get("pi").get,
      nonProfitStatus = mappedKVPs.get("nonProfitStatus").get,
      linkedNihUsername = mappedKVPs.get("linkedNihUsername"),
      linkExpireTime = mappedKVPs.get("linkExpireTime") match {
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

case class NIHLink (linkedNihUsername: String, linkExpireTime: Long, isDbgapAuthorized: Boolean) extends mappedPropVals {
  require(ProfileValidator.nonEmpty(linkedNihUsername), "linkedNihUsername must be non-empty")
}

object ProfileValidator {
  private val emailRegex = """^([\w-]+(?:\.[\w-]+)*)@((?:[\w-]+\.)*\w[\w-]{0,66})\.([a-z]{2,6}(?:\.[a-z]{2})?)$""".r
  def nonEmpty(field: String): Boolean = !field.trim.isEmpty
  def nonEmpty(field: Option[String]): Boolean = !field.getOrElse("").trim.isEmpty
  def emptyOrValidEmail(field: Option[String]): Boolean = field match {
    case None => true
    case Some(x) if x.isEmpty => true
    case Some(x) if emailRegex.findFirstMatchIn(x).isDefined => true
    case _ => false
  }
}

trait mappedPropVals {
  def propertyValueMap: Map[String, String] = {
    this.getClass.getDeclaredFields map {
      f =>
        f.setAccessible(true)
        f.get(this) match {
          case x: String => f.getName -> x
          case y: Option[_] => f.getName -> y.asInstanceOf[Option[_]].getOrElse("").toString
          case z => f.getName -> z.toString
        }
    } toMap
  }
}
