package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes._
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.{IO, Outcome}
import cats.implicits.toTraverseOps
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.{ExternalCredsDAO, GoogleServicesDAO, SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.firecloud.{
  Application,
  FireCloudConfig,
  FireCloudException,
  FireCloudExceptionWithErrorReport
}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.workbench.client.sam.model.{BulkMembershipUpdateRequestV2, PolicyMembershipUpdate}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.util2.messaging.ReceivedMessage
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import spray.json._

import java.time.Instant
import java.util
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Try}

case class NihStatus(linkedNihUsername: Option[String] = None,
                     datasetPermissions: Set[NihDatasetPermission],
                     linkExpireTime: Option[Long] = None
)

case class NihAllowlist(name: String,
                        groupToSync: WorkbenchGroupName,
                        fileName: String,
                        dbGapPermission: DbGapPermission,
                        disabled: Boolean
)

case class NihDatasetPermission(name: String, authorized: Boolean)

case class NihResources(datasetPermissions: Set[NihDatasetPermission])

object NihStatus {
  implicit val impNihDatasetPermission: RootJsonFormat[NihDatasetPermission] = jsonFormat2(NihDatasetPermission)
  implicit val impNihStatus: RootJsonFormat[NihStatus] = jsonFormat3(NihStatus.apply)
  implicit val impNihResources: RootJsonFormat[NihResources] = jsonFormat1(NihResources)
}

object NihService {
  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new NihService(app.samDAO, app.thurloeDAO, app.googleServicesDAO, app.ecmDAO)
}

class NihService(val samDao: SamDAO,
                 val thurloeDao: ThurloeDAO,
                 val googleDao: GoogleServicesDAO,
                 val ecmDao: ExternalCredsDAO
)(implicit val executionContext: ExecutionContext)
    extends LazyLogging
    with SprayJsonSupport {

  lazy val log = LoggerFactory.getLogger(getClass)

  def getAdminAccessToken: WithAccessToken = UserInfo(googleDao.getAdminUserAccessToken, "")

  private val nihAllowlists: Set[NihAllowlist] = FireCloudConfig.Nih.whitelists
  private val enabledNihAllowlists = FireCloudConfig.Nih.whitelists.filterNot(_.disabled)

  def processExternalCredsMessage(externalCredsMessage: ReceivedMessage[ExternalCredsMessage]): IO[Unit] = {
    val groupUpdateIO = for {
      visas <- IO.fromFuture(
        IO(
          ecmDao.getVisas(
            externalCredsMessage.msg.providerName,
            externalCredsMessage.msg.userId,
            FireCloudConfig.Nih.rasIssuer,
            FireCloudConfig.Nih.rasVisaType,
            getAdminAccessToken
          )
        )
      )
      userDbGapPermissions = extractDbGapPermissionsFromVisas(visas)
      groupUpdates = determineGroupUpdates(externalCredsMessage.msg.userId, userDbGapPermissions)
      _ <- ensureDbGapGroupsExist()
      _ <- logMessageHandling(groupUpdates, externalCredsMessage.msg)
      _ <- IO.fromFuture(IO(samDao.bulkUpdateGroups(groupUpdates, getAdminAccessToken)))
    } yield externalCredsMessage.ackHandler.ack()

    groupUpdateIO.handleError { e =>
      logger.error(s"Error processing ${externalCredsMessage.msg}: ${e.getMessage}", e)
      externalCredsMessage.ackHandler.nack()
    }
  }

  private def logMessageHandling(groupUpdates: List[BulkMembershipUpdateRequestV2], message: ExternalCredsMessage) =
    IO {
      val (addGroups, removeGroups) =
        groupUpdates.partition(_.getPolicyUpdates.asScala.exists(Option(_).forall(_.getRemoveUserIds.isEmpty)))
      logger.info(
        s"Handling $message, adding to groups: [${addGroups.map(_.getResourceId).mkString(", ")}], removing from groups: [${removeGroups.map(_.getResourceId).mkString(", ")}]"
      )
    }

  private def ensureDbGapGroupsExist(): IO[Unit] =
    for {
      groups <- IO.fromFuture(IO(samDao.listGroups(getAdminAccessToken)))
      groupNames = groups.map(_.groupName.toLowerCase).toSet
      missingGroupNames = FireCloudConfig.Nih.dbGapPermissionToGroup.values.toSet -- groupNames
      _ <- missingGroupNames.toList.traverse { groupName =>
        IO.fromFuture(IO(samDao.createGroup(WorkbenchGroupName(groupName))(getAdminAccessToken)))
      }
    } yield ()

  private def determineGroupUpdates(userId: String, userDbGapPermissions: Seq[DbGapPermission]) =
    FireCloudConfig.Nih.dbGapPermissionToGroup.map { case (permission, group) =>
      val policyMembershipUpdate = new PolicyMembershipUpdate().policyName(FireCloudConfig.Sam.groupMemberPolicy)
      if (userDbGapPermissions.contains(permission)) {
        policyMembershipUpdate.addAddUserIdsItem(userId)
      } else {
        policyMembershipUpdate.addRemoveUserIdsItem(userId)
      }
      new BulkMembershipUpdateRequestV2()
        .resourceTypeName(FireCloudConfig.Sam.groupResourceType)
        .resourceId(group)
        .addPolicyUpdatesItem(policyMembershipUpdate)
    }.toList

  private def extractDbGapPermissionsFromVisas(visas: Seq[AnyRef]) =
    for {
      visa <- visas
      visaMap = visa.asInstanceOf[util.Map[String, Object]].asScala
      dbGapPermissions = visaMap
        .getOrElse("ras_dbgap_permissions", new util.ArrayList[Object]())
        .asInstanceOf[util.List[Object]]
        .asScala
      dbGapPermission <- dbGapPermissions.map(_.asInstanceOf[util.Map[String, Object]].asScala)
      if dbGapPermission("expiration").asInstanceOf[Number].longValue() > Instant.now.getEpochSecond
    } yield DbGapPermission(PhsId(dbGapPermission("phs_id").asInstanceOf[String]),
                            ConsentGroup(dbGapPermission("consent_group").asInstanceOf[String])
    )

  def getNihStatus(userInfo: UserInfo): Future[PerRequestMessage] =
    getNihStatusFromEcm(userInfo).flatMap {
      case Some(nihStatus) =>
        logger.info("Found eRA Commons link in ECM for user " + userInfo.id)
        Future.successful(RequestComplete(nihStatus))
      case None =>
        getNihStatusFromThurloe(userInfo).map {
          case Some(nihStatus) =>
            logger.info("Found eRA Commons link in Thurloe for user " + userInfo.id)
            RequestComplete(nihStatus)
          case None => RequestComplete(NotFound)
        }
    }

  def getNihResources(userInfo: UserInfo): Future[NihResources] =
    getAllAllowlistGroupMemberships(userInfo).map { allowlistMembership =>
      NihResources(allowlistMembership)
    }

  private def getNihStatusFromEcm(userInfo: UserInfo): Future[Option[NihStatus]] =
    ecmDao.getLinkedAccount(userInfo).flatMap {
      case Some(linkedAccount) =>
        getAllAllowlistGroupMemberships(userInfo).map { allowlistMembership =>
          Some(
            NihStatus(Some(linkedAccount.linkedExternalId),
                      allowlistMembership,
                      Some(linkedAccount.linkExpireTime.getMillis / 1000L)
            )
          )
        }
      case None => Future.successful(None)
    }

  private def getNihStatusFromThurloe(userInfo: UserInfo): Future[Option[NihStatus]] =
    thurloeDao.getAllKVPs(userInfo.id, userInfo) flatMap {
      case Some(profileWrapper) =>
        ProfileUtils.getString("linkedNihUsername", profileWrapper) match {
          case Some(linkedNihUsername) =>
            getAllAllowlistGroupMemberships(userInfo).map { allowlistMembership =>
              val linkExpireTime = ProfileUtils.getLong("linkExpireTime", profileWrapper)
              Some(NihStatus(Some(linkedNihUsername), allowlistMembership, linkExpireTime))
            }
          case None => Future.successful(None)
        }
      case None => Future.successful(None)
    }

  // Since the RAS release, the dbGaP groups (e.g. dbgap_phs000424_c1) managed via
  // dbGapPermissionToGroup are the correct source for dataset permissions. The legacy
  // NihAllowlist-based groups are no longer in use.
  private def getAllAllowlistGroupMemberships(userInfo: UserInfo): Future[Set[NihDatasetPermission]] = {
    val groupMemberships = samDao.listGroups(userInfo)
    groupMemberships.map { groups =>
      val samGroupNames = groups.map(g => WorkbenchGroupName(g.groupName)).toSet
      FireCloudConfig.Nih.dbGapPermissionToGroup.map { case (_, groupName) =>
        NihDatasetPermission(groupName, samGroupNames.contains(WorkbenchGroupName(groupName)))
      }.toSet
    }
  }

  private def downloadNihAllowlist(allowlist: NihAllowlist): Set[String] =
    if (allowlist.disabled) {
      logger.info(s"NIH allowlist ${allowlist.name} is disabled, skipping download")
      Set.empty
    } else {
      val usersList = Source.fromInputStream(
        googleDao.getBucketObjectAsInputStream(FireCloudConfig.Nih.whitelistBucket, allowlist.fileName)
      )

      usersList.getLines().toSet
    }

  def syncAllowlistAllUsers(allowlistName: String): Future[PerRequestMessage] = {
    logger.info("Synchronizing allowlist '" + allowlistName + "' for all users")
    // include disabled allowlists so we remove all group users during the sync
    nihAllowlists.find(_.name.equals(allowlistName)) match {
      case Some(allowlist) =>
        val allowlistSyncResults = syncNihAllowlistAllUsers(allowlist)
        allowlistSyncResults map { _ => RequestComplete(NoContent) }

      case None => Future.successful(RequestComplete(NotFound))
    }
  }

  // This syncs all of the allowlists for all of the users
  def syncAllNihAllowlistsAllUsers(): Future[PerRequestMessage] = {
    logger.info("Synchronizing all allowlists for all users")
    // include disabled allowlists so we remove all group users during the sync
    val allowlistSyncResults = Future.traverse(nihAllowlists)(syncNihAllowlistAllUsers)

    allowlistSyncResults map { _ => RequestComplete(NoContent) }
  }

  private def getNihAllowlistTerraEmailsFromEcm(allowlistEraUsernames: Set[String]): Future[Set[WorkbenchEmail]] =
    for {
      // The list of users that, according to ECM, have active links
      allLinkedAccounts <- ecmDao.getActiveLinkedEraAccounts(getAdminAccessToken)
      // The list of linked accounts which for which the user appears in the allowlist
      allowlistLinkedAccounts = allLinkedAccounts.filter(linkedAccount =>
        allowlistEraUsernames.contains(linkedAccount.linkedExternalId)
      )
      // The users from Sam for the linked accounts on the allowlist
      users <- samDao.getUsersForIds(allowlistLinkedAccounts.map(la => WorkbenchUserId(la.userId)))(getAdminAccessToken)
    } yield users.map(user => WorkbenchEmail(user.userEmail)).toSet

  private def getNihAllowlistTerraEmailsFromThurloe(allowlistEraUsernames: Set[String]): Future[Set[WorkbenchEmail]] =
    for {
      // The list of users that, according to Thurloe, have active links and are
      // on the specified allowlist
      subjectIds <- getCurrentNihUsernameMap(thurloeDao) map { mapping =>
        mapping.collect { case (fcUser, nihUser) if allowlistEraUsernames contains nihUser => fcUser }.toSeq
      }
      // The users from Sam for the linked accounts on the allowlist
      users <- samDao.getUsersForIds(subjectIds.map(WorkbenchUserId))(getAdminAccessToken)
    } yield users.map(user => WorkbenchEmail(user.userEmail)).toSet

  // This syncs the specified allowlist in full
  private def syncNihAllowlistAllUsers(nihAllowlist: NihAllowlist): Future[Unit] = {
    val allowlistUsers = downloadNihAllowlist(nihAllowlist)
    val dbGapSamGroup =
      FireCloudConfig.Nih.dbGapPermissionToGroup.get(nihAllowlist.dbGapPermission).map(WorkbenchGroupName)

    for {
      dbGapGroupEmail <- Future.traverse(dbGapSamGroup.toList)(getSamGroupEmail)
      ecmEmails <- getNihAllowlistTerraEmailsFromEcm(allowlistUsers)
      thurloeEmails <- getNihAllowlistTerraEmailsFromThurloe(allowlistUsers)
      members = allowedNihMembers(ecmEmails ++ thurloeEmails ++ dbGapGroupEmail.flatten)
      _ <- ensureAllowlistGroupsExists()
      // The request to Sam to completely overwrite the group with the list of actively linked users on the allowlist
      _ <- samDao.overwriteGroupMembers(nihAllowlist.groupToSync, ManagedGroupRoles.Member, members.toList)(
        getAdminAccessToken
      ) recoverWith { case e: Exception =>
        throw new FireCloudException(s"Error synchronizing NIH allowlist: ${e.getMessage}")
      }
    } yield ()
  }

  private def getSamGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchEmail]] =
    samDao.getGroupEmail(groupName)(getAdminAccessToken).map(Option.apply).recover {
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode.contains(StatusCodes.NotFound) =>
        None
    }

  private def allowedNihMembers(members: Set[WorkbenchEmail]): Set[WorkbenchEmail] = {
    val allowedMembers =
      members.filterNot(email => FireCloudConfig.Nih.denyEmailPatterns.exists(_.matches(email.value)))
    val deniedMembers = members -- allowedMembers
    if (deniedMembers.nonEmpty) {
      logger.info(
        s"NIH allowlist sync: ${deniedMembers.mkString(",")} were denied access to the NIH allowlist due to matching deny patterns"
      )
    }
    allowedMembers
  }

  private def unlinkNihAccount(userInfo: UserInfo): Future[Unit] =
    for {
      _ <- unlinkNihAccountEcm(userInfo)
      _ <- unlinkNihAccountThurloe(userInfo)
    } yield ()

  private def unlinkNihAccountEcm(userInfo: UserInfo): Future[Unit] =
    ecmDao.deleteLinkedEraAccount(userInfo, getAdminAccessToken)

  private def unlinkNihAccountThurloe(userInfo: UserInfo): Future[Unit] = {
    val nihKeys = Set("linkedNihUsername", "linkExpireTime")

    Future.traverse(nihKeys) { nihKey =>
      thurloeDao.deleteKeyValue(userInfo.id, nihKey, userInfo)
    } map { results =>
      val failedKeys = results.collect { case Failure(exception) =>
        exception.getMessage
      }

      if (failedKeys.nonEmpty) {
        throw new FireCloudExceptionWithErrorReport(
          ErrorReport(StatusCodes.InternalServerError, s"Unable to unlink NIH account: ${failedKeys.mkString(",")}")
        )
      }
    }
  }

  def unlinkNihAccountAndSyncSelf(userInfo: UserInfo): Future[Unit] =
    for {
      _ <- unlinkNihAccount(userInfo)
      _ <- ensureAllowlistGroupsExists()
      _ <- Future.traverse(enabledNihAllowlists) { allowlist =>
        removeUserFromNihAllowlistGroup(WorkbenchEmail(userInfo.userEmail), allowlist).recoverWith {
          case _: Exception =>
            throw new FireCloudExceptionWithErrorReport(
              ErrorReport(StatusCodes.InternalServerError, "Unable to unlink NIH account")
            )
        }
      }
    } yield {}

  private def syncNihAllowlistForUser(userEmail: WorkbenchEmail,
                                      linkedNihUserName: String,
                                      nihAllowlist: NihAllowlist
  ): Future[Boolean] = {
    val allowlistUsers = downloadNihAllowlist(nihAllowlist)

    if (allowlistUsers.contains(linkedNihUserName) && allowedNihMembers(Set(userEmail)).contains(userEmail)) {
      for {
        _ <- samDao.addGroupMember(nihAllowlist.groupToSync, ManagedGroupRoles.Member, userEmail)(getAdminAccessToken)
      } yield true
    } else {
      for {
        _ <- samDao.removeGroupMember(nihAllowlist.groupToSync, ManagedGroupRoles.Member, userEmail)(
          getAdminAccessToken
        )
      } yield false
    }
  }

  private def removeUserFromNihAllowlistGroup(userEmail: WorkbenchEmail, nihAllowlist: NihAllowlist): Future[Unit] =
    samDao.removeGroupMember(nihAllowlist.groupToSync, ManagedGroupRoles.Member, userEmail)(getAdminAccessToken)

  private def ensureAllowlistGroupsExists(): Future[Unit] =
    samDao.listGroups(getAdminAccessToken).flatMap { groups =>
      val missingGroupNames =
        enabledNihAllowlists.map(_.groupToSync.value.toLowerCase()) -- groups.map(_.groupName.toLowerCase).toSet
      if (missingGroupNames.isEmpty) {
        Future.successful(())
      } else {
        Future
          .traverse(missingGroupNames) { groupName =>
            samDao.createGroup(WorkbenchGroupName(groupName))(getAdminAccessToken).recover {
              case fce: FireCloudExceptionWithErrorReport
                  if fce.errorReport.statusCode.contains(StatusCodes.Conflict) => // somebody else made it
            }
          }
          .map(_ => ())
      }
    }

  def filterForCurrentUsers(usernames: Map[String, String], expirations: Map[String, String]): Map[String, String] = {
    val currentFcUsers = expirations
      .map { case (fcUser, expStr: String) =>
        fcUser -> Try(expStr.toLong).toOption
      }
      .collect {
        case (fcUser, Some(exp: Long)) if DateUtils.now < exp => fcUser
      }
      .toSet

    usernames.filter { case (fcUser, nihUser) => currentFcUsers.contains(fcUser) }
  }

  // get a mapping of FireCloud user name to NIH User name, for only those Thurloe users with a non-expired NIH link
  private def getCurrentNihUsernameMap(thurloeDAO: ThurloeDAO): Future[Map[String, String]] = {
    val nihUsernames = thurloeDAO.getAllUserValuesForKey("linkedNihUsername")
    val nihExpireTimes = thurloeDAO.getAllUserValuesForKey("linkExpireTime")

    for {
      usernames <- nihUsernames
      expirations <- nihExpireTimes
    } yield filterForCurrentUsers(usernames, expirations)
  }
}
