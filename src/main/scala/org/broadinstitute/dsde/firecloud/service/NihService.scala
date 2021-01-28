package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.Try


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
}

object NihService {
  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new NihService(app.samDAO, app.thurloeDAO, app.googleServicesDAO)
}

class NihService(val samDao: SamDAO, val thurloeDao: ThurloeDAO, val googleDao: GoogleServicesDAO)
                (implicit val executionContext: ExecutionContext) extends LazyLogging with SprayJsonSupport {

  def GetNihStatus(userInfo: UserInfo) = getNihStatus(userInfo)
  def UpdateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink) = updateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink)
  def SyncAllWhitelists = syncAllNihWhitelistsAllUsers
  def SyncWhitelist(whitelistName: String) = syncWhitelistAllUsers(whitelistName)

  def getAdminAccessToken: WithAccessToken = UserInfo(googleDao.getAdminUserAccessToken, "")

  val nihWhitelists: Set[NihWhitelist] = FireCloudConfig.Nih.whitelists

  def getNihStatus(userInfo: UserInfo): Future[PerRequestMessage] = {
    thurloeDao.getAllKVPs(userInfo.id, userInfo) flatMap {
      case Some(profileWrapper) =>
        ProfileUtils.getString("linkedNihUsername", profileWrapper) match {
          case Some(linkedNihUsername) =>
            Future.traverse(nihWhitelists) { whitelistDef =>
              samDao.isGroupMember(whitelistDef.groupToSync, userInfo).map(isMember => NihDatasetPermission(whitelistDef.name, isMember))
            }.map { whitelistMembership =>
              val linkExpireTime = ProfileUtils.getLong("linkExpireTime", profileWrapper)
              RequestComplete(NihStatus(Some(linkedNihUsername), whitelistMembership, linkExpireTime))
            }
          case None => Future.successful(RequestComplete(NotFound))
        }
      case None => Future.successful(RequestComplete(NotFound))
    } recover {
      case e:Exception => RequestCompleteWithErrorReport(InternalServerError, e.getMessage, e)
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
      _ <- samDao.overwriteGroupMembers(nihWhitelist.groupToSync, ManagedGroupRoles.Member, members)(getAdminAccessToken) recoverWith {
        case _: Exception => throw new FireCloudException("Error synchronizing NIH whitelist")
      }
    } yield ()
  }

  private def linkNihAccount(userInfo: UserInfo, nihLink: NihLink): Future[Try[Unit]] = {
    val profilePropertyMap = nihLink.propertyValueMap

    thurloeDao.saveKeyValues(userInfo, profilePropertyMap)
  }

  def updateNihLinkAndSyncSelf(userInfo: UserInfo, nihLink: NihLink): Future[PerRequestMessage] = {
    for {
      linkResult <- linkNihAccount(userInfo, nihLink)
      whitelistSyncResults <- Future.traverse(nihWhitelists) {
        whitelist => syncNihWhitelistForUser(WorkbenchEmail(userInfo.userEmail), nihLink.linkedNihUsername, whitelist)
          .map(NihDatasetPermission(whitelist.name, _))
      }
    } yield {
      if (linkResult.isSuccess) {
        RequestComplete(OK, NihStatus(Option(nihLink.linkedNihUsername), whitelistSyncResults, Option(nihLink.linkExpireTime)))
      } else {
        RequestCompleteWithErrorReport(InternalServerError, "Error updating NIH link")
      }
    }
  }

  private def syncNihWhitelistForUser(userEmail: WorkbenchEmail, linkedNihUserName: String, nihWhitelist: NihWhitelist): Future[Boolean] = {
    val whitelistUsers = downloadNihWhitelist(nihWhitelist)

    if(whitelistUsers contains linkedNihUserName) {
      for {
        _ <- ensureWhitelistGroupExists(nihWhitelist.groupToSync)
        _ <- samDao.addGroupMember(nihWhitelist.groupToSync, ManagedGroupRoles.Member, userEmail)(getAdminAccessToken)
      } yield true
    } else Future.successful(false)
  }

  private def ensureWhitelistGroupExists(groupName: WorkbenchGroupName): Future[Unit] = {
    samDao.listGroups(getAdminAccessToken).flatMap {
      case groups if groups.map(_.groupName.toLowerCase).contains(groupName.value.toLowerCase) => Future.successful(())
      case _ => samDao.createGroup(groupName)(getAdminAccessToken).recover {
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
