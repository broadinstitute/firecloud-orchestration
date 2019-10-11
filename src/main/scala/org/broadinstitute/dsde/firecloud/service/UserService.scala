package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impProfileWrapper, impTerraPreference, impUserImportPermission}
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, ProfileWrapper, RequestCompleteWithErrorReport, TerraPreference, UserImportPermission, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.UserService.{DeleteTerraPreference, GetAllUserKeys, GetTerraPreference, ImportPermission, SetTerraPreference}
import org.broadinstitute.dsde.rawls.model.WorkspaceAccessLevels
import org.parboiled.common.FileUtils
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}



object UserService {
  sealed trait UserServiceMessage

  val TerraPreferenceKey = "preferTerra"
  val TerraPreferenceLastUpdatedKey = "preferTerraLastUpdated"
  val AnonymousGroupKey = "anonymousGroup"
  val ContactEmailKey = "contactEmail"
  // TODO: check permissions from https://github.com/aaronbassett/Pass-phrase
  val randomNounList: List[String] = FileUtils.readAllTextFromResource("nouns_ab.txt").split("\n").toList
  val randomAdjectiveList: List[String] = FileUtils.readAllTextFromResource("adjectives_ab.txt").split("\n").toList


  case object ImportPermission extends UserServiceMessage
  case object GetTerraPreference extends UserServiceMessage
  case object SetTerraPreference extends UserServiceMessage
  case object DeleteTerraPreference extends UserServiceMessage
  case object GetAllUserKeys extends UserServiceMessage

  def props(userService: (UserInfo) => UserService, userInfo: UserInfo): Props = {
    Props(userService(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new UserService(app.rawlsDAO, app.thurloeDAO, app.googleServicesDAO, userInfo)

}

class UserService(rawlsDAO: RawlsDAO, thurloeDAO: ThurloeDAO, googleServicesDAO: GoogleServicesDAO, userToken: UserInfo)(implicit protected val executionContext: ExecutionContext)
  extends Actor with LazyLogging with SprayJsonSupport with DefaultJsonProtocol {

  override def receive = {
    case ImportPermission => importPermission(userToken) pipeTo sender
    case GetTerraPreference => getTerraPreference(userToken) pipeTo sender
    case SetTerraPreference => setTerraPreference(userToken) pipeTo sender
    case DeleteTerraPreference => deleteTerraPreference(userToken) pipeTo sender
    case GetAllUserKeys => getAllUserKeys(userToken) pipeTo sender
  }

  def importPermission(implicit userToken: UserInfo): Future[PerRequestMessage] = {
    // start two requests, in parallel, to fire off workspace list and billing project list
    val billingProjects = rawlsDAO.getProjects
    val workspaces = rawlsDAO.getWorkspaces

    // for-comprehension to extract from the two vals
    // we intentionally only check write access on the workspace (not canCompute); write access without
    // canCompute is an edge case that PO does not feel is worth the effort. The workspace list does not return
    // canCompute, so the effort is somewhat high.
    for {
      hasProject <- billingProjects.map(_.exists(_.creationStatus == CreationStatuses.Ready))
      hasWorkspace <- workspaces.map { ws => ws.exists(_.accessLevel.compare(WorkspaceAccessLevels.Write) >= 0) }
    } yield
      RequestComplete(StatusCodes.OK, UserImportPermission(
        billingProject = hasProject,
        writableWorkspace = hasWorkspace))
  }

  private def getProfileValue(profileWrapper: ProfileWrapper, targetKey: String): Option[String] = {
    profileWrapper.keyValuePairs
      .find(_.key.contains(targetKey)) // .find returns Option[FireCloudKeyValue]
      .flatMap(_.value) // .value returns Option[String]
  }

  def getTerraPreference(implicit userToken: UserInfo): Future[PerRequestMessage] = {
    // so, so many nested Options ...
    val futurePref: Future[TerraPreference] = thurloeDAO.getAllKVPs(userToken.id, userToken) map { // .getAllKVPs returns Option[ProfileWrapper]
      case None => TerraPreference(true, 0)
      case Some(wrapper) => {
        val pref: Boolean = Try(getProfileValue(wrapper, UserService.TerraPreferenceKey).getOrElse("true").toBoolean)
          .toOption.getOrElse(true)
        val updated: Long = Try(getProfileValue(wrapper, UserService.TerraPreferenceLastUpdatedKey).getOrElse("0").toLong)
          .toOption.getOrElse(0L)
        TerraPreference(pref, updated)
      }
    }

    futurePref map { pref: TerraPreference => RequestComplete(pref) }
  }

  def setTerraPreference(userToken: UserInfo): Future[PerRequestMessage] = {
    writeTerraPreference(userToken, prefValue = true)
  }

  def deleteTerraPreference(userToken: UserInfo): Future[PerRequestMessage] = {
    writeTerraPreference(userToken, prefValue = false)
  }

  private def writeTerraPreference(userToken: UserInfo, prefValue: Boolean): Future[PerRequestMessage] = {
    val kvpsToUpdate = Map(
      UserService.TerraPreferenceKey -> prefValue.toString,
      UserService.TerraPreferenceLastUpdatedKey -> System.currentTimeMillis().toString
    )

    logger.info(s"${userToken.userEmail} (${userToken.id}) setting Terra preference to $prefValue")

    thurloeDAO.saveKeyValues(userToken, kvpsToUpdate) flatMap {
      case Failure(exception) => Future(RequestCompleteWithErrorReport(StatusCodes.InternalServerError,
        "could not save Terra preference", exception))
      case Success(_) => getTerraPreference(userToken)
    }
  }

  private def getWord(iL: Long, wordList: List[String]): String = {
    val modIndex: Int = (math.abs(iL) % (wordList.length - 1)).toInt
    wordList(modIndex)
  }

  def deleteAnonymousGroup(userToken: UserInfo): Future[PerRequestMessage] = {
    writeAnonymousGroup(userToken, anonymousGroupName = "")
  }

  private def writeAnonymousGroup(userToken: UserInfo, anonymousGroupName: String): Future[PerRequestMessage] = {
    val kvpsToUpdate = Map(
      UserService.AnonymousGroupKey -> anonymousGroupName
    )

    logger.info(s"${userToken.userEmail} (${userToken.id}) setting anonymousGroup to $anonymousGroupName")

    thurloeDAO.saveKeyValues(userToken, kvpsToUpdate) flatMap {
      case Failure(exception) => Future(RequestCompleteWithErrorReport(StatusCodes.InternalServerError,
        "could not save Anonymous Group", exception))
      case Success(_) => {
        val futureAllKeys: Future[ProfileWrapper] = getAllKeysFromThurloe(userToken)
        futureAllKeys map { keys => RequestComplete(keys)}
      }
    }
  }

  private def getAnonymousGroup: String = {
    // randomly generate the anonymousGroupName, which follows format: adjective-noun-endOfUUID
    val anonymousGroupUUID: UUID = UUID.randomUUID()
    val anonymousGroupName: String = (FireCloudConfig.FireCloud.supportPrefix
      + getWord(anonymousGroupUUID.getMostSignificantBits(), UserService.randomAdjectiveList) + "-"
      + getWord(anonymousGroupUUID.getLeastSignificantBits(), UserService.randomNounList) + "-"
      + anonymousGroupUUID.toString().split("-")(4))
    anonymousGroupName
  }

  private def getAllKeysFromThurloe(userToken: UserInfo): Future[ProfileWrapper] = {
    thurloeDAO.getAllKVPs(userToken.id, userToken) map { // .getAllKVPs returns Option[ProfileWrapper]
      case None => ProfileWrapper(userToken.id, List())
      case Some(wrapper) => {
        wrapper
      }
    }
  }

  private def callCreateGoogleGroup(keys: ProfileWrapper, anonymousGroupName: String): Future[PerRequestMessage] = {
    // define userEmail to add to google Group - check first for contactEmail, otherwise use user's login email
    val userEmail = getProfileValue(keys, UserService.ContactEmailKey) match {
      case None | Some("") => userToken.userEmail // if the contactEmail key doesn't exist OR (|) if there's no contactEmail set in the profile
      case Some(contactEmail) => contactEmail // if there is a non-empty value set for contactEmail, we assume contactEmail is a valid email
    }

    googleServicesDAO.createGoogleGroup(anonymousGroupName, userEmail) match { // this will return "" if group creation is not successful
      case None | Some("") => {
        Future(RequestComplete(keys)) // this creates a Future[PerRequestMessage]
      }
      case Some(groupEmailName) => {
        writeAnonymousGroup(userToken, groupEmailName)
      } // this returns a Future[PerRequestMessage]
    }
  }

  def getAllUserKeys(userToken: UserInfo): Future[PerRequestMessage] = {
    val futureKeys:Future[ProfileWrapper] = getAllKeysFromThurloe(userToken)
    futureKeys flatMap { keys: ProfileWrapper => // flatMap to keep the updated value of futureKeys
      getProfileValue(keys, UserService.AnonymousGroupKey) match { // getProfileValue returns Option[String]
        case Some(anonymousGroupName) => { // if anonymousGroup key exists, return the keys
          // check if Google Group actually exists and create it if not
          googleServicesDAO.checkGoogleGroupExists(anonymousGroupName) match { // this will return "" if group does not exist
            case None | Some("") => {
              callCreateGoogleGroup(keys, anonymousGroupName)
            }
            case Some(_) => {}
          }
          Future(RequestComplete(keys)) // this creates a Future[PerRequestMessage]
        }
        case None => { // if anonymousGroup key does not exist, create one and return the keys
          // randomly generate an anonymousGroup
          val groupEmail: String = getAnonymousGroup + "@" + FireCloudConfig.FireCloud.supportDomain
          // create Google Group and add userEmail to anonymousGroupEmail
          callCreateGoogleGroup(keys, groupEmail)
        }
      }
    } // flatMap (on a future input) executes the call to get the actual (not future) keys
  }
}