package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impProfileWrapper, impTerraPreference, impUserImportPermission}
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses
import org.broadinstitute.dsde.firecloud.model.{ProfileWrapper, RequestCompleteWithErrorReport, TerraPreference, UserImportPermission, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.rawls.model.WorkspaceAccessLevels
import org.parboiled.common.FileUtils
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}



object UserService {
  val TerraPreferenceKey = "preferTerra"
  val TerraPreferenceLastUpdatedKey = "preferTerraLastUpdated"
  val AnonymousGroupKey = "anonymousGroup"
  val ContactEmailKey = "contactEmail"
  // following lists are governed by this license (https://aaron.mit-license.org/) and come from https://github.com/aaronbassett/Pass-phrase
  val randomNounList: List[String] = FileUtils.readAllTextFromResource("nouns_ab.txt").split("\n").toList
  val randomAdjectiveList: List[String] = FileUtils.readAllTextFromResource("adjectives_ab.txt").split("\n").toList

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new UserService(app.rawlsDAO, app.thurloeDAO, app.googleServicesDAO, userInfo)

}

class UserService(rawlsDAO: RawlsDAO, thurloeDAO: ThurloeDAO, googleServicesDAO: GoogleServicesDAO, userToken: UserInfo)(implicit protected val executionContext: ExecutionContext)
  extends LazyLogging with SprayJsonSupport with DefaultJsonProtocol {

  def ImportPermission = importPermission(userToken)
  def GetTerraPreference = getTerraPreference(userToken)
  def SetTerraPreference = setTerraPreference(userToken)
  def DeleteTerraPreference = deleteTerraPreference(userToken)
  def GetAllUserKeys = getAllUserKeys(userToken)

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

  def getNewAnonymousGroupName: String = {
    // randomly generate the anonymousGroupName, which follows format: terra-user-adjective-noun-endOfUUID@supportdomain.org
    val anonymousGroupUUID: UUID = UUID.randomUUID()
    val anonymousGroupName: String = ( FireCloudConfig.FireCloud.supportPrefix
      + getWord(anonymousGroupUUID.getMostSignificantBits(), UserService.randomAdjectiveList) + "-"
      + getWord(anonymousGroupUUID.getLeastSignificantBits(), UserService.randomNounList) + "-"
      + anonymousGroupUUID.toString().split("-")(4)
      + "@" + FireCloudConfig.FireCloud.supportDomain )
    anonymousGroupName
  }

  private def getAllKeysFromThurloe(userToken: UserInfo): Future[ProfileWrapper] = {
    thurloeDAO.getAllKVPs(userToken.id, userToken) map { // .getAllKVPs returns Option[ProfileWrapper]
      case None => ProfileWrapper(userToken.id, List())
      case Some(wrapper) => wrapper
    }
  }

  /**
    * creates a new anonymized Google group for the user and adds the user's contact email to the new Google group.
    * note that if any step fails, we just return the keys so the user's experience is not interrupted.
    * @param keys                 the user's KVPs
    * @param anonymousGroupName   sets the name of the Google group to be created
    * @return                     Future[PerRequestMessage] for all KVPs for the user
    */
  def setupAnonymizedGoogleGroup(keys: ProfileWrapper, anonymousGroupName: String): Future[PerRequestMessage] = {
    // define userEmail to add to google Group - check first for contactEmail, otherwise use user's login email
    val userEmail = getProfileValue(keys, UserService.ContactEmailKey) match {
      case None | Some ("") => userToken.userEmail
      case Some (contactEmail) => contactEmail // if there is a non-empty value set for contactEmail, we assume contactEmail is a valid email
    }

    // create the new anonymized Google group
    googleServicesDAO.createGoogleGroup(anonymousGroupName) match { // returns Option.empty if group creation not successful
      case None => {
        Future(RequestComplete(keys))
      }
      case Some(groupEmailName) => {
        // if Google group creation was successful, add the user's email address to the group
        googleServicesDAO.addMemberToAnonymizedGoogleGroup(groupEmailName, userEmail) match { // returns Option.empty if user addition is not successful
          case None => {
            Future(RequestComplete(keys))
          }
          case Some(_) => {
            // only if the anonymized Google group was successfully created and user email added to group
            writeAnonymousGroup(userToken, groupEmailName) // write new KVP to Thurloe
          }
        }
      }
    }
  }

  /**
    * gets all KVPs for the user from Thurloe
    *   - checks whether the key `anonymousGroup` exists
    *     - if `anonymousGroup` KVP exists for user, return all keys.
    *     - if that key does not exist, we:
    *       - generate an anonymized Google group for the user and add the user's email address to the group
    *       - set that anonymized Google group email address as the value for `anonymousGroup` KVP
    *       - return all keys, including new `anonymousGroup` KVP
    * @param userToken
    * @return
    */
  def getAllUserKeys(userToken: UserInfo): Future[PerRequestMessage] = {
    val futureKeys:Future[ProfileWrapper] = getAllKeysFromThurloe(userToken)
    futureKeys flatMap { keys: ProfileWrapper =>
      getProfileValue(keys, UserService.AnonymousGroupKey) match { // getProfileValue returns Option[String]
        case None | Some("") => {
          setupAnonymizedGoogleGroup(keys, getNewAnonymousGroupName)
        }
        case Some(_) => {
          Future(RequestComplete(keys))
        }
      }
    }
  }
}
