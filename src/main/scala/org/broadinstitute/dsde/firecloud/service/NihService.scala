package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.{ExternalCredsDAO, GoogleServicesDAO, SamDAO, ShibbolethDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName, WorkbenchUserId}
import org.slf4j.LoggerFactory
import pdi.jwt.{Jwt, JwtAlgorithm}
import spray.json.DefaultJsonProtocol._
import spray.json._

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
  implicit val impNihDatasetPermission: RootJsonFormat[NihDatasetPermission] = jsonFormat2(NihDatasetPermission)
  implicit val impNihStatus: RootJsonFormat[NihStatus] = jsonFormat3(NihStatus.apply)
}

object NihService {
  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new NihService(app.samDAO, app.thurloeDAO, app.googleServicesDAO, app.shibbolethDAO, app.ecmDAO)
}

class NihService(val samDao: SamDAO, val thurloeDao: ThurloeDAO, val googleDao: GoogleServicesDAO, val shibbolethDao: ShibbolethDAO, val ecmDao: ExternalCredsDAO)
                (implicit val executionContext: ExecutionContext) extends LazyLogging with SprayJsonSupport {

  lazy val log = LoggerFactory.getLogger(getClass)

  def getAdminAccessToken: WithAccessToken = UserInfo(googleDao.getAdminUserAccessToken, "")

  val nihWhitelists: Set[NihWhitelist] = FireCloudConfig.Nih.whitelists

  def getNihStatus(userInfo: UserInfo): Future[PerRequestMessage] = {
    getNihStatusFromEcm(userInfo).flatMap {
      case Some(nihStatus) =>
        logger.info("Found eRA Commons link in ECM for user " + userInfo.id)
        Future.successful(RequestComplete(nihStatus))
      case None => getNihStatusFromThurloe(userInfo).map {
        case Some(nihStatus) =>
          logger.info("Found eRA Commons link in Thurloe for user " + userInfo.id)
          RequestComplete(nihStatus)
        case None => RequestComplete(NotFound)
      }
    }
  }

  private def getNihStatusFromEcm(userInfo: UserInfo): Future[Option[NihStatus]] = {
    ecmDao.getLinkedAccount(userInfo).flatMap {
      case Some(linkedAccount) => getAllAllowlistGroupMemberships(userInfo).map { whitelistMembership =>
          Some(NihStatus(Some(linkedAccount.linkedExternalId), whitelistMembership, Some(linkedAccount.linkExpireTime.getMillis / 1000L)))
        }
      case None => Future.successful(None)
    }
  }

  private def getNihStatusFromThurloe(userInfo: UserInfo): Future[Option[NihStatus]] = {
    thurloeDao.getAllKVPs(userInfo.id, userInfo) flatMap {
      case Some(profileWrapper) =>
        ProfileUtils.getString("linkedNihUsername", profileWrapper) match {
          case Some(linkedNihUsername) => getAllAllowlistGroupMemberships(userInfo).map { whitelistMembership =>
              val linkExpireTime = ProfileUtils.getLong("linkExpireTime", profileWrapper)
              Some(NihStatus(Some(linkedNihUsername), whitelistMembership, linkExpireTime))
            }
          case None => Future.successful(None)
        }
      case None => Future.successful(None)
    }
  }

  private def getAllAllowlistGroupMemberships(userInfo: UserInfo): Future[Set[NihDatasetPermission]] = {
    Future.traverse(nihWhitelists) { whitelistDef =>
      samDao.isGroupMember(whitelistDef.groupToSync, userInfo).map(isMember => NihDatasetPermission(whitelistDef.name, isMember))
    }
  }

  private def downloadNihAllowlist(allowlist: NihWhitelist): Set[String] = {
    val usersList = Source.fromInputStream(googleDao.getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, allowlist.fileName))

    usersList.getLines().toSet
  }

  def syncWhitelistAllUsers(whitelistName: String): Future[PerRequestMessage] = {
    logger.info("Synchronizing allowlist '" + whitelistName + "' for all users")
    nihWhitelists.find(_.name.equals(whitelistName)) match {
      case Some(whitelist) =>
        val whitelistSyncResults = syncNihAllowlistAllUsers(whitelist)
        whitelistSyncResults map { _ => RequestComplete(NoContent) }

      case None => Future.successful(RequestComplete(NotFound))
    }
  }

  // This syncs all of the whitelists for all of the users
  def syncAllNihWhitelistsAllUsers(): Future[PerRequestMessage] = {
    logger.info("Synchronizing all allowlists for all users")
    val whitelistSyncResults = Future.traverse(nihWhitelists)(syncNihAllowlistAllUsers)

    whitelistSyncResults map { _ => RequestComplete(NoContent) }
  }

  private def getNihAllowlistTerraEmailsFromEcm(allowlistEraUsernames: Set[String]): Future[Set[WorkbenchEmail]] =
    for {
      // The list of users that, according to ECM, have active links
      allLinkedAccounts <- ecmDao.getActiveLinkedEraAccounts(getAdminAccessToken)
      // The list of linked accounts which for which the user appears in the allowlist
      allowlistLinkedAccounts = allLinkedAccounts.filter(linkedAccount => allowlistEraUsernames.contains(linkedAccount.linkedExternalId))
      // The users from Sam for the linked accounts on the allowlist
      users <- samDao.getUsersForIds(allowlistLinkedAccounts.map(la => WorkbenchUserId(la.userId)))(getAdminAccessToken)
    } yield users.map(user => WorkbenchEmail(user.userEmail)).toSet

  private def getNihAllowlistTerraEmailsFromThurloe(allowlistEraUsernames: Set[String]): Future[Set[WorkbenchEmail]] =
    for {
      // The list of users that, according to Thurloe, have active links and are
      // on the specified whitelist
      subjectIds <- getCurrentNihUsernameMap(thurloeDao) map { mapping =>
        mapping.collect { case (fcUser, nihUser) if allowlistEraUsernames contains nihUser => fcUser }.toSeq
      }

      //Sam APIs don't consume subject IDs. Now we must look up the emails in Thurloe...
      members <- thurloeDao.getAllUserValuesForKey("email").map { keyValues =>
        keyValues.view.filterKeys(subjectId => subjectIds.contains(subjectId)).values.map(WorkbenchEmail).toList
      }
    } yield members.toSet

  // This syncs the specified whitelist in full
  private def syncNihAllowlistAllUsers(nihAllowlist: NihWhitelist): Future[Unit] = {
    val whitelistUsers = downloadNihAllowlist(nihAllowlist)

    for {
      ecmEmails <- getNihAllowlistTerraEmailsFromEcm(whitelistUsers)
      thurloeEmails <- getNihAllowlistTerraEmailsFromThurloe(whitelistUsers)
      members = ecmEmails ++ thurloeEmails
      _ <- ensureAllowlistGroupsExists()
      // The request to Sam to completely overwrite the group with the list of actively linked users on the allowlist
      _ <- samDao.overwriteGroupMembers(nihAllowlist.groupToSync, ManagedGroupRoles.Member, members.toList)(getAdminAccessToken) recoverWith {
        case e: Exception => throw new FireCloudException(s"Error synchronizing NIH whitelist: ${e.getMessage}")
      }
    } yield ()
  }

  private def linkNihAccountEcm(userInfo: UserInfo, nihLink: NihLink): Future[Try[Unit]] = {
    ecmDao.putLinkedEraAccount(LinkedEraAccount(userInfo.id, nihLink))(getAdminAccessToken)
    .flatMap(_ => {
      logger.info("Successfully linked NIH account in ECM for user " + userInfo.id)
      Future.successful(Success())
    }).recoverWith {
      case e =>
        logger.warn("Failed to link NIH account in ECM for user" + userInfo.id)
        Future.successful(Failure(e))
    }
  }

  private def linkNihAccountThurloe(userInfo: UserInfo, nihLink: NihLink): Future[Try[Unit]] = {
    val profilePropertyMap = nihLink.propertyValueMap

    thurloeDao.saveKeyValues(userInfo, profilePropertyMap)
  }

  private def unlinkNihAccount(userInfo: UserInfo): Future[Unit] = {
    for {
      _ <- unlinkNihAccountEcm(userInfo)
      _ <- unlinkNihAccountThurloe(userInfo)
    } yield ()
  }

  private def unlinkNihAccountEcm(userInfo: UserInfo): Future[Unit] = {
    ecmDao.deleteLinkedEraAccount(userInfo)(getAdminAccessToken)
  }

  private def unlinkNihAccountThurloe(userInfo: UserInfo): Future[Unit] = {
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
      _ <- ensureAllowlistGroupsExists()
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
      thurloeLinkResult <- linkNihAccountThurloe(userInfo, nihLink)
      ecmLinkResult <- linkNihAccountEcm(userInfo, nihLink)

      _ <- ensureAllowlistGroupsExists()
      whitelistSyncResults <- Future.traverse(nihWhitelists) {
        whitelist => syncNihWhitelistForUser(WorkbenchEmail(userInfo.userEmail), nihLink.linkedNihUsername, whitelist)
          .map(NihDatasetPermission(whitelist.name, _))
      }
    } yield {
      if (thurloeLinkResult.isSuccess && ecmLinkResult.isSuccess) {
        RequestComplete(OK, NihStatus(Option(nihLink.linkedNihUsername), whitelistSyncResults, Option(nihLink.linkExpireTime)))
      } else {
        (thurloeLinkResult, ecmLinkResult) match {
          case (Failure(t), Success(_)) => logger.error("Failed to link NIH Account in Thurloe", t)
          case (Success(_), Failure(t)) => logger.error("Failed to link NIH Account in ECM", t)
          case (Failure(t1), Failure(t2)) => logger.error("Failed to link NIH Account in Thurloe and ECM", t1, t2)
        }
        RequestCompleteWithErrorReport(InternalServerError, "Error updating NIH link")
      }
    }

    res.recoverWith {
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(BadRequest) =>
        Future.successful(RequestCompleteWithErrorReport(BadRequest, e.errorReport.message))
    }
  }

  private def syncNihWhitelistForUser(userEmail: WorkbenchEmail, linkedNihUserName: String, nihWhitelist: NihWhitelist): Future[Boolean] = {
    val whitelistUsers = downloadNihAllowlist(nihWhitelist)

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

  private def ensureAllowlistGroupsExists(): Future[Unit] = {
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
  private def getCurrentNihUsernameMap(thurloeDAO: ThurloeDAO): Future[Map[String, String]] = {
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
