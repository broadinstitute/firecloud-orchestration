package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, ActorRefFactory, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.NihService.{GetNihStatus, SyncWhitelist, UpdateNIHLinkAndSyncSelf}
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
  isDbgapAuthorized: Option[Boolean] = None,
  linkExpireTime: Option[Long] = None,
  descriptionUntilExpires: Option[String] = None
)

object NihStatus {
  implicit val impNihStatus = jsonFormat4(NihStatus.apply)

  def apply(profile: Profile): NihStatus = {
    apply(profile, profile.isDbgapAuthorized)
  }

  def apply(profile: Profile, isDbGapAuthorized: Option[Boolean]): NihStatus = {
    new NihStatus(
      profile.linkedNihUsername,
      isDbGapAuthorized,
      profile.linkExpireTime
    )
  }
}

object NihService {
  sealed trait ServiceMessage
  case class GetNihStatus(userInfo: UserInfo) extends ServiceMessage
  case class UpdateNIHLinkAndSyncSelf(userInfo: UserInfo, nihLink: NIHLink) extends ServiceMessage
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
    case UpdateNIHLinkAndSyncSelf(userInfo: UserInfo, nihLink: NIHLink) => updateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NIHLink) pipeTo sender
    case SyncWhitelist => syncWhitelistFull() pipeTo sender
  }
}

trait NihService extends LazyLogging {
  implicit val executionContext: ExecutionContext
  val rawlsDao: RawlsDAO
  val thurloeDao: ThurloeDAO
  val googleDao: GoogleServicesDAO

  def getNihStatus(userInfo: UserInfo): Future[PerRequestMessage] = {
    thurloeDao.getProfile(userInfo) flatMap {
      case Some(profile) =>
        profile.linkedNihUsername match {
          case Some(_) =>
            rawlsDao.isDbGapAuthorized(userInfo) map { isDbGapAuthorized =>
              RequestComplete(NihStatus(profile.copy(linkExpireTime = Option(profile.linkExpireTime.getOrElse(0L))), Some(isDbGapAuthorized)))
            }
          case None => Future.successful(RequestComplete(NotFound))
        }
      case None => Future.successful(RequestComplete(NotFound))
    }
  }

  def updateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NIHLink): Future[PerRequestMessage] = {
    val syncWhiteListResult = syncWhitelistUser(userInfo.getUniqueId, nihLink.linkedNihUsername)
    val profilePropertyMap = nihLink.propertyValueMap
    val propertyUpdates = thurloeDao.saveKeyValues(userInfo, profilePropertyMap)
    val profileResponse = propertyUpdates.map { responses =>
      val allSucceeded = responses.forall(_.isSuccess)

      allSucceeded match {
        case true => RequestComplete(OK)
        case false => RequestCompleteWithErrorReport(InternalServerError, "Error updating NIH link.")
      }
    }

    syncWhiteListResult flatMap { _ => profileResponse } recover { case t => RequestCompleteWithErrorReport(InternalServerError, "Error updating NIH link", t) }
  }

  private def getWhitelist(): Set[String] = {
    val (bucket, file) = (FireCloudConfig.Nih.whitelistBucket, FireCloudConfig.Nih.whitelistFile)
    val whitelist = Source.fromInputStream(googleDao.getBucketObjectAsInputStream(bucket, file))

    whitelist.getLines().toSet
  }

  // This syncs the entire whitelist
  def syncWhitelistFull(): Future[PerRequestMessage] = {
    val whitelist = getWhitelist()

    // The list of users that, according to Thurloe, have active links and are
    // on the dbGap whitelist
    val nihEnabledFireCloudUsers = getCurrentNihUsernameMap(thurloeDao) map { mapping =>
      mapping.collect { case (fcUser, nihUser) if whitelist contains nihUser => fcUser }.toSeq
    }

    val memberList = nihEnabledFireCloudUsers map { subjectIds => {
      RawlsGroupMemberList(userSubjectIds = Some(subjectIds))
    }}

    // The request to rawls to completely overwrite the dbGapAuthorizedUsers group
    // with the list of actively linked users on the whitelist
    val rawlsRequest = memberList flatMap { members =>
      rawlsDao.adminOverwriteGroupMembership(FireCloudConfig.Nih.rawlsGroupName, members)
    }

    rawlsRequest map { response =>
      if(response) RequestComplete(NoContent)
      else RequestCompleteWithErrorReport(InternalServerError, "Error synchronizing NIH whitelist.")
    }
  }

  // This syncs an individual user with the whitelist, used when NIH linking (dbGap)
  def syncWhitelistUser(subjectId: String, linkedNihUsername: String): Future[PerRequestMessage] = {
    val whitelist = getWhitelist()

    val memberList = if(whitelist contains linkedNihUsername) {
      RawlsGroupMemberList(userSubjectIds = Some(Seq(subjectId)))
    } else throw new FireCloudExceptionWithErrorReport(ErrorReport(InternalServerError, "Error updating NIH link."))

    rawlsDao.adminAddMemberToGroup(FireCloudConfig.Nih.rawlsGroupName, memberList) map { response =>
      if(response) RequestComplete(NoContent)
      else RequestCompleteWithErrorReport(InternalServerError, "Error updating NIH link.")
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
