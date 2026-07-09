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
    getNihStatusFromThurloe(userInfo).map {
      case Some(nihStatus) => RequestComplete(nihStatus)
      case None            => RequestComplete(NotFound)
    }

  def getNihResources(userInfo: UserInfo): Future[NihResources] =
    getAllAllowlistGroupMemberships(userInfo).map { allowlistMembership =>
      NihResources(allowlistMembership)
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

  def unlinkNihAccountThurloe(userInfo: UserInfo): Future[Unit] = {
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
}
