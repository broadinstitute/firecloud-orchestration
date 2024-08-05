package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, SamDAO, ShibbolethDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.slf4j.LoggerFactory
import pdi.jwt.{Jwt, JwtAlgorithm}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.reflect.runtime.universe.{MethodSymbol, typeOf}
import scala.util.{Failure, Try}


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
  implicit val impNihDatasetPermission: RootJsonFormat[NihDatasetPermission] = jsonFormat2(NihDatasetPermission)
  implicit val impNihStatus: RootJsonFormat[NihStatus] = jsonFormat3(NihStatus.apply)
}

object NihService {
  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new NihService(app.samDAO, app.thurloeDAO, app.googleServicesDAO, app.shibbolethDAO)
}

class NihService(val samDao: SamDAO, val thurloeDao: ThurloeDAO, val googleDao: GoogleServicesDAO, val shibbolethDao: ShibbolethDAO)
                (implicit val executionContext: ExecutionContext) extends LazyLogging with SprayJsonSupport {

  lazy val log = LoggerFactory.getLogger(getClass)

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
  def syncAllNihWhitelistsAllUsers(): Future[PerRequestMessage] = {
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
        keyValues.view.filterKeys(subjectId => subjectIds.contains(subjectId)).values.map(WorkbenchEmail).toList
      }

      _ <- ensureWhitelistGroupsExists()

      // The request to rawls to completely overwrite the group
      // with the list of actively linked users on the whitelist
      _ <- samDao.overwriteGroupMembers(nihWhitelist.groupToSync, ManagedGroupRoles.Member, members)(getAdminAccessToken) recoverWith {
        case e: Exception => throw new FireCloudException(s"Error synchronizing NIH whitelist: ${e.getMessage}")
      }
    } yield ()
  }

  private def linkNihAccount(userInfo: UserInfo, nihLink: NihLink): Future[Try[Unit]] = {
    val profilePropertyMap = nihLink.propertyValueMap

    thurloeDao.saveKeyValues(userInfo, profilePropertyMap)
  }

  private def unlinkNihAccount(userInfo: UserInfo): Future[Unit] = {
    val nihKeys = Set("linkedNihUsername", "linkExpireTime")

    Future.traverse(nihKeys) { nihKey =>
      thurloeDao.deleteKeyValue(userInfo.id, nihKey, userInfo)
    } map { results =>
      val failedKeys = results.collect {
        case Failure(exception) => exception.getMessage
      }

      if(failedKeys.nonEmpty) {
        throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, s"Unable to unlink NIH account: ${failedKeys.mkString(",")}"))
      }
    }
  }

  def unlinkNihAccountAndSyncSelf(userInfo: UserInfo): Future[Unit] = {
    for {
      _ <- unlinkNihAccount(userInfo)
      _ <- ensureWhitelistGroupsExists()
      _ <- Future.traverse(nihWhitelists) {
        whitelist => removeUserFromNihWhitelistGroup(WorkbenchEmail(userInfo.userEmail), whitelist).recoverWith {
          case _: Exception => throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Unable to unlink NIH account"))
        }
      }
    } yield {}
  }

  def updateNihLinkAndSyncSelf(userInfo: UserInfo, jwtWrapper: JWTWrapper): Future[PerRequestMessage] = {
    val res = for {
      shibbolethPublicKey <- shibbolethDao.getPublicKey()
      decodedToken <- Future.fromTry(Jwt.decodeRawAll(jwtWrapper.jwt, shibbolethPublicKey, Seq(JwtAlgorithm.RS256))).recoverWith {
        // The exception's error message contains the raw JWT. For an abundance of security, don't
        // log the error message - even though if we reached this point, the JWT is invalid. It could
        // still contain sensitive info.
        case _: Throwable => Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Failed to decode JWT")))
      }
      nihLink = decodedToken match {
        case (_, rawTokenFromShibboleth, _) =>
          rawTokenFromShibboleth.parseJson.convertTo[ShibbolethToken].toNihLink
      }
      linkResult <- linkNihAccount(userInfo, nihLink)
      _ <- ensureWhitelistGroupsExists()
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

    res.recoverWith {
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(BadRequest) =>
        Future.successful(RequestCompleteWithErrorReport(BadRequest, e.errorReport.message))
    }
  }

  private def syncNihWhitelistForUser(userEmail: WorkbenchEmail, linkedNihUserName: String, nihWhitelist: NihWhitelist): Future[Boolean] = {
    val whitelistUsers = downloadNihWhitelist(nihWhitelist)

    if(whitelistUsers contains linkedNihUserName) {
      for {
        _ <- samDao.addGroupMember(nihWhitelist.groupToSync, ManagedGroupRoles.Member, userEmail)(getAdminAccessToken)
      } yield true
    } else {
      for {
        _ <- samDao.removeGroupMember(nihWhitelist.groupToSync, ManagedGroupRoles.Member, userEmail)(getAdminAccessToken)
      } yield false
    }
  }

  private def removeUserFromNihWhitelistGroup(userEmail: WorkbenchEmail, nihWhitelist: NihWhitelist): Future[Unit] = {
    samDao.removeGroupMember(nihWhitelist.groupToSync, ManagedGroupRoles.Member, userEmail)(getAdminAccessToken)
  }

  private def ensureWhitelistGroupsExists(): Future[Unit] = {
    samDao.listGroups(getAdminAccessToken).flatMap { groups =>
      val missingGroupNames = nihWhitelists.map(_.groupToSync.value.toLowerCase()) -- groups.map(_.groupName.toLowerCase).toSet
      if (missingGroupNames.isEmpty) {
        Future.successful(())
      } else {
        Future.traverse(missingGroupNames) { groupName =>
          samDao.createGroup(WorkbenchGroupName(groupName))(getAdminAccessToken).recover {
            case fce: FireCloudExceptionWithErrorReport if fce.errorReport.statusCode.contains(StatusCodes.Conflict) => // somebody else made it
          }
        }.map(_ => ())
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
