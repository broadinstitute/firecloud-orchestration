package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, ActorRefFactory, Props}
import akka.http.scaladsl.model.StatusCodes
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO, SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.NihService.{GetNihStatus, SyncAllWhitelists, SyncWhitelist, UpdateNihLinkAndSyncSelf}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success, Try}


case class NihStatus(
                      linkedNihUsername: Option[String] = None,
                      datasetPermissions: Set[NihDatasetPermission],
                      linkExpireTime: Option[Long] = None)

case class NihWhitelist(
                         name: String,
                         groupToSync: WorkbenchGroupName,
                         fileName: String)

case class NihDatasetPermission(name: String, authorized: Boolean)

object NihStatus {
  implicit val impNihDatasetPermission = jsonFormat2(NihDatasetPermission)
  implicit val impNihStatus = jsonFormat3(NihStatus.apply)

  def apply(profile: Profile, whitelistAccess: Set[NihDatasetPermission]): NihStatus = {
    new NihStatus(
      profile.linkedNihUsername,
      whitelistAccess,
      profile.linkExpireTime
    )
  }
}

object NihService {
  sealed trait ServiceMessage
  final case class GetNihStatus(userInfo: UserInfo) extends ServiceMessage
  final case class UpdateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink) extends ServiceMessage
  case object SyncAllWhitelists extends ServiceMessage
  final case class SyncWhitelist(whitelistName: String) extends ServiceMessage

  def props(service: () => NihServiceActor): Props = {
    Props(service())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new NihServiceActor(app.samDAO, app.rawlsDAO, app.thurloeDAO, app.googleServicesDAO)
}

class NihServiceActor(val samDao: SamDAO, val rawlsDao: RawlsDAO, val thurloeDao: ThurloeDAO, val googleDao: GoogleServicesDAO)
  (implicit val executionContext: ExecutionContext) extends Actor with NihService {

  override def receive = {
    case GetNihStatus(userInfo: UserInfo) => getNihStatus(userInfo) pipeTo sender
    case UpdateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink) => updateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink) pipeTo sender
    case SyncAllWhitelists => syncAllNihWhitelistsAllUsers pipeTo sender
    case SyncWhitelist(whitelistName) => syncWhitelistAllUsers(whitelistName) pipeTo sender
  }
}

trait NihService extends LazyLogging {
  implicit val executionContext: ExecutionContext
  val samDao: SamDAO
  val rawlsDao: RawlsDAO
  val thurloeDao: ThurloeDAO
  val googleDao: GoogleServicesDAO

  def getAdminAccessToken: WithAccessToken = UserInfo(googleDao.getAdminUserAccessToken, "")

  val nihWhitelists: Set[NihWhitelist] = FireCloudConfig.Nih.whitelists

  def getNihStatus(userInfo: UserInfo): Future[PerRequestMessage] = {
    thurloeDao.getProfile(userInfo) flatMap {
      case Some(profile) =>
        profile.linkedNihUsername match {
          case Some(_) =>
            Future.traverse(nihWhitelists) { whitelistDef =>
              samDao.isGroupMember(whitelistDef.groupToSync, userInfo).map(isMember => NihDatasetPermission(whitelistDef.name, isMember))
            }.map { whitelistMembership =>
              RequestComplete(NihStatus(profile, whitelistMembership))
            }
          case None => Future.successful(RequestComplete(NotFound))
        }
      case None => Future.successful(RequestComplete(NotFound))
    }
  }

  private def downloadNihWhitelist(whitelist: NihWhitelist): Set[String] = {
    val usersList = Source.fromInputStream(googleDao.getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, whitelist.fileName))

    usersList.getLines().toSet
  }

  def syncWhitelistAllUsers(whitelistName: String): Future[PerRequestMessage] = {
    nihWhitelists.find(_.name.equals(whitelistName)) match {
      case Some(whitelist) =>
        val whitelistSyncResults = syncNihWhitelistAllUsers(whitelist)
        whitelistSyncResults map { _ => RequestComplete(NoContent) }

      case None => Future.successful(RequestComplete(NotFound))
    }
  }

  // This syncs all of the whitelists for all of the users
  def syncAllNihWhitelistsAllUsers: Future[PerRequestMessage] = {
    val whitelistSyncResults = Future.traverse(nihWhitelists)(syncNihWhitelistAllUsers)

    whitelistSyncResults map { _ => RequestComplete(NoContent) }
  }

  // This syncs the specified whitelist in full
  def syncNihWhitelistAllUsers(nihWhitelist: NihWhitelist): Future[Unit] = {
    val whitelistUsers = downloadNihWhitelist(nihWhitelist)

    for {
      // The list of users that, according to Thurloe, have active links and are
      // on the specified whitelist
      subjectIds <- getCurrentNihUsernameMap(thurloeDao) map { mapping =>
        mapping.collect { case (fcUser, nihUser) if whitelistUsers contains nihUser => fcUser }.toSeq
      }

      //Sam APIs don't consume subject IDs. Now we must look up the emails in Thurloe...
      members <- thurloeDao.getAllUserValuesForKey("email").map { keyValues =>
        keyValues.filterKeys(subjectId => subjectIds.contains(subjectId)).values.map(WorkbenchEmail).toList
      }

      _ <- ensureWhitelistGroupExists(nihWhitelist.groupToSync)

      // The request to rawls to completely overwrite the group
      // with the list of actively linked users on the whitelist
      _ <- rawlsDao.overwriteGroupMembership(nihWhitelist.groupToSync, ManagedGroupRoles.Member, RawlsGroupMemberList(Option(members.map(_.value)), None, None, None))(getAdminAccessToken) recoverWith {
        case _: Exception => throw new FireCloudException("Error synchronizing NIH whitelist")
      }
    } yield ()
  }

  private def linkNihAccount(userInfo: UserInfo, nihLink: NihLink): Future[Try[Unit]] = {
    val profilePropertyMap = nihLink.propertyValueMap

    thurloeDao.saveKeyValues(userInfo, profilePropertyMap)
  }

  def updateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink): Future[PerRequestMessage] = {
    val linkResult = linkNihAccount(userInfo, nihLink)

    val whitelistSyncResults = Future.traverse(nihWhitelists) { whitelist =>
      syncNihWhitelistForUser(WorkbenchEmail(userInfo.userEmail), nihLink.linkedNihUsername, whitelist).map(NihDatasetPermission(whitelist.name, _))
    }

    linkResult.flatMap { response =>
      if(response.isSuccess) {
        whitelistSyncResults.map { datasetPermissions =>
          RequestComplete(OK, NihStatus(Option(nihLink.linkedNihUsername), datasetPermissions, Option(nihLink.linkExpireTime)))
        }
      }
      else Future.successful(RequestCompleteWithErrorReport(InternalServerError, "Error updating NIH link"))
    }
  }

  private def syncNihWhitelistForUser(userEmail: WorkbenchEmail, linkedNihUserName: String, nihWhitelist: NihWhitelist): Future[Boolean] = {
    val whitelistUsers = downloadNihWhitelist(nihWhitelist)

    if(whitelistUsers contains linkedNihUserName) {
      for {
        _ <- ensureWhitelistGroupExists(nihWhitelist.groupToSync)
        _ <- rawlsDao.addMemberToGroup(nihWhitelist.groupToSync, ManagedGroupRoles.Member, userEmail)(getAdminAccessToken)
      } yield true
    } else Future.successful(false)
  }

  private def ensureWhitelistGroupExists(groupName: WorkbenchGroupName): Future[Unit] = {
    rawlsDao.getGroupsForUser(getAdminAccessToken).flatMap {
      case groups if groups.map(_.toLowerCase).contains(groupName.value.toLowerCase) => Future.successful(())
      case _ => rawlsDao.createGroup(groupName)(getAdminAccessToken).recover {
        case fce: FireCloudExceptionWithErrorReport if fce.errorReport.statusCode.contains(StatusCodes.Conflict) => // somebody else made it
      }
    }
  }

  def filterForCurrentUsers(usernames: Map[String, String], expirations: Map[String, String]): Map[String, String] = {
    val currentFcUsers = expirations.map {
      case (fcUser, expStr: String) => fcUser -> Try { expStr.toLong }.toOption
    }.collect {
      case (fcUser, Some(exp: Long)) if DateUtils.now < exp => fcUser
    }.toSet

    usernames.filter { case (fcUser, nihUser) => currentFcUsers.contains(fcUser) }
  }

  // get a mapping of FireCloud user name to NIH User name, for only those Thurloe users with a non-expired NIH link
  def getCurrentNihUsernameMap(thurloeDAO: ThurloeDAO): Future[Map[String, String]] = {
    val nihUsernames = thurloeDAO.getAllUserValuesForKey("linkedNihUsername")
    val nihExpireTimes = thurloeDAO.getAllUserValuesForKey("linkExpireTime")

    for {
      usernames <- nihUsernames
      expirations <- nihExpireTimes
    } yield {
      filterForCurrentUsers(usernames, expirations)
    }
  }
}
