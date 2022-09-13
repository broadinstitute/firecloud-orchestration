package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.service.NihDatasetPermission
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import spray.json.DefaultJsonProtocol._

import scala.language.postfixOps
import scala.util.Try

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

case class ProfileKVP(userId: String, keyValuePair: FireCloudKeyValue)

case class BasicProfile (
    firstName: String,
    lastName: String,
    title: String,
    contactEmail: Option[String],
    institute: String,
    programLocationCity: String,
    programLocationState: String,
    programLocationCountry: String,
    termsOfService: Option[String],
    researchArea: Option[String]
  ) extends mappedPropVals {
  require(ProfileValidator.nonEmpty(firstName), "first name must be non-empty")
  require(ProfileValidator.nonEmpty(lastName), "last name must be non-empty")
  require(ProfileValidator.nonEmpty(title), "title must be non-empty")
  require(ProfileValidator.emptyOrValidEmail(contactEmail), "contact email must be valid or empty")
  require(ProfileValidator.nonEmpty(institute), "institute must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationCity), "program location city must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationState), "program location state must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationCountry), "program location country must be non-empty")
}

case class Profile (
    firstName: String,
    lastName: String,
    title: String,
    contactEmail: Option[String],
    institute: String,
    programLocationCity: String,
    programLocationState: String,
    programLocationCountry: String,
    researchArea: Option[String],
    linkedNihUsername: Option[String] = None,
    linkExpireTime: Option[Long] = None
  ) extends mappedPropVals {
  require(ProfileValidator.nonEmpty(firstName), "first name must be non-empty")
  require(ProfileValidator.nonEmpty(lastName), "last name must be non-empty")
  require(ProfileValidator.nonEmpty(title), "title must be non-empty")
  require(ProfileValidator.emptyOrValidEmail(contactEmail), "contact email must be valid or empty")
  require(ProfileValidator.nonEmpty(institute), "institute must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationCity), "program location city must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationState), "program location state must be non-empty")
  require(ProfileValidator.nonEmpty(programLocationCountry), "program location country must be non-empty")
}

object Profile {

  // increment this number every time you make a change to the user-provided profile fields
  val currentVersion:Int = 4

  val requiredKeys = List("firstName", "lastName", "title", "institute", "programLocationCity",
                          "programLocationState", "programLocationCountry")

  def apply(wrapper: ProfileWrapper): Profile = {
    val mappedKVPs: Map[String, String] = (wrapper.keyValuePairs collect {
      case fckv: FireCloudKeyValue if fckv.key.nonEmpty && fckv.value.nonEmpty => fckv.key.get -> fckv.value.get
    }).toMap

    requiredKeys foreach {req =>
      assert(mappedKVPs.contains(req), s"Profile for user ${wrapper.userId} must contain a key-value entry for $req")
    }

    new Profile(
      firstName = mappedKVPs("firstName"),
      lastName = mappedKVPs("lastName"),
      title = mappedKVPs("title"),
      contactEmail = mappedKVPs.get("contactEmail"),
      institute = mappedKVPs("institute"),
      programLocationCity = mappedKVPs("programLocationCity"),
      programLocationState = mappedKVPs("programLocationState"),
      programLocationCountry = mappedKVPs("programLocationCountry"),
      researchArea = mappedKVPs.get("researchArea"),
      linkedNihUsername = mappedKVPs.get("linkedNihUsername"),
      linkExpireTime = mappedKVPs.get("linkExpireTime") match {
        case Some(time) => Some(time.toLong)
        case _ => None
      }
    )
  }

}

case class NihLink(linkedNihUsername: String, linkExpireTime: Long) extends mappedPropVals {
  require(ProfileValidator.nonEmpty(linkedNihUsername), "linkedNihUsername must be non-empty")
}

// For parsing the decoded JWT received from Shibboleth
case class ShibbolethToken(eraCommonsUsername: String, iat: Long) {
  // Link should expire in 30 days
  def toNihLink: NihLink = NihLink(eraCommonsUsername, iat + 60 * 60 * 24 * 30)
}

object ProfileValidator {
  private val emailRegex = """^([\w-\+]+(?:\.[\w-\+]+)*)@((?:[\w-]+\.)*\w[\w-]{0,66})\.([a-z]{2,6}(?:\.[a-z]{2})?)$""".r
  def nonEmpty(field: String): Boolean = !field.trim.isEmpty
  def nonEmpty(field: Option[String]): Boolean = !field.getOrElse("").trim.isEmpty
  def emptyOrValidEmail(field: Option[String]): Boolean = field match {
    case None => true
    case Some(x) if x.isEmpty => true
    case Some(x) if emailRegex.findFirstMatchIn(x).isDefined => true
    case _ => false
  }
}

object ProfileUtils {
  def getString(key: String, profileWrapper: ProfileWrapper): Option[String] = {
    profileWrapper.keyValuePairs.collectFirst {
      case fckv if fckv.key.contains(key) => fckv.value
    }.flatten
  }
  def getLong(key: String, profileWrapper: ProfileWrapper): Option[Long] = {
    getString(key, profileWrapper).flatMap(x => Try(x.toLong).toOption)
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

case class TerraPreference(preferTerra: Boolean, preferTerraLastUpdated: Long)
