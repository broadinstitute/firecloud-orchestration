package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, ActorRefFactory, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.NihService.{GetNihStatus, SyncWhitelist, UpdateNihLinkAndSyncSelf}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.rawls.model.ErrorReport
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
                         groupToSync: String,
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
  case class GetNihStatus(userInfo: UserInfo) extends ServiceMessage
  case class UpdateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink) extends ServiceMessage
  case object SyncWhitelist extends ServiceMessage

  def props(service: () => NihServiceActor): Props = {
    Props(service())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new NihServiceActor(app.rawlsDAO, app.thurloeDAO, app.googleServicesDAO)
}

class NihServiceActor(val rawlsDao: RawlsDAO, val thurloeDao: ThurloeDAO, val googleDao: GoogleServicesDAO)
  (implicit val executionContext: ExecutionContext) extends Actor with NihService {

  override def receive = {
    case GetNihStatus(userInfo: UserInfo) => getNihStatus(userInfo) pipeTo sender
    case UpdateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink) => updateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink) pipeTo sender
    case SyncWhitelist => syncAllNihWhitelistsAllUsers pipeTo sender
  }
}

trait NihService extends LazyLogging {
  implicit val executionContext: ExecutionContext
  val rawlsDao: RawlsDAO
  val thurloeDao: ThurloeDAO
  val googleDao: GoogleServicesDAO

  val nihWhitelists: Set[NihWhitelist] = FireCloudConfig.Nih.whitelists

  def getNihStatus(userInfo: UserInfo): Future[PerRequestMessage] = {
    thurloeDao.getProfile(userInfo) flatMap {
      case Some(profile) =>
        profile.linkedNihUsername match {
          case Some(_) =>
            Future.traverse(nihWhitelists) { whitelistDef =>
              rawlsDao.isGroupMember(userInfo, whitelistDef.groupToSync).map(isMember => NihDatasetPermission(whitelistDef.name, isMember))
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

  // This syncs all of the whitelists for all of the users
  def syncAllNihWhitelistsAllUsers: Future[PerRequestMessage] = {
    val whitelistSyncResults = Future.traverse(nihWhitelists)(syncNihWhitelistAllUsers)

    whitelistSyncResults map { response =>
      if(response.forall(isSuccess => isSuccess)) RequestComplete(NoContent)
      else RequestCompleteWithErrorReport(InternalServerError, "Error synchronizing NIH whitelist")
    }
  }

  // This syncs the specified whitelist in full
  def syncNihWhitelistAllUsers(nihWhitelist: NihWhitelist): Future[Boolean] = {
    val whitelistUsers = downloadNihWhitelist(nihWhitelist)

    // The list of users that, according to Thurloe, have active links and are
    // on the specified whitelist
    val nihEnabledFireCloudUsers = getCurrentNihUsernameMap(thurloeDao) map { mapping =>
      mapping.collect { case (fcUser, nihUser) if whitelistUsers contains nihUser => fcUser }.toSeq
    }

    val memberList = nihEnabledFireCloudUsers map { subjectIds => {
      RawlsGroupMemberList(userSubjectIds = Some(subjectIds))
    }}

    // The request to rawls to completely overwrite the group
    // with the list of actively linked users on the whitelist
    memberList flatMap { members =>
      rawlsDao.adminOverwriteGroupMembership(nihWhitelist.groupToSync, members)
    }
  }

  private def linkNihAccount(userInfo: UserInfo, nihLink: NihLink): Future[Try[Unit]] = {
    val profilePropertyMap = nihLink.propertyValueMap

    thurloeDao.saveKeyValues(userInfo, profilePropertyMap)
  }

  def updateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink): Future[PerRequestMessage] = {
    val linkResult = linkNihAccount(userInfo, nihLink)

    val whitelistSyncResults = Future.traverse(nihWhitelists) { whitelist =>
      syncNihWhitelistForUser(userInfo.getUniqueId, nihLink.linkedNihUsername, whitelist).map(NihDatasetPermission(whitelist.name, _))
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

  private def syncNihWhitelistForUser(subjectId: String, linkedNihUserName: String, nihWhitelist: NihWhitelist): Future[Boolean] = {
    val whitelistUsers = downloadNihWhitelist(nihWhitelist)

    if(whitelistUsers contains linkedNihUserName) {
      val memberList = RawlsGroupMemberList(userSubjectIds = Some(Seq(subjectId)))

      rawlsDao.adminAddMemberToGroup(nihWhitelist.groupToSync, memberList)
    } else Future.successful(false)
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
