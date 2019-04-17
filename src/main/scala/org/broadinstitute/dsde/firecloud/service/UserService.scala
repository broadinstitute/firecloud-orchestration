package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impUserImportPermission, impTerraPreference}
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, ProfileWrapper, RequestCompleteWithErrorReport, TerraPreference, UserImportPermission, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.UserService.{DeleteTerraPreference, GetTerraPreference, ImportPermission, SetTerraPreference}
import org.broadinstitute.dsde.rawls.model.WorkspaceAccessLevels
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object UserService {
  sealed trait UserServiceMessage

  val TerraPreferenceKey = "preferTerra"
  val TerraPreferenceLastUpdatedKey = "preferTerraLastUpdated"

  case object ImportPermission extends UserServiceMessage
  case object GetTerraPreference extends UserServiceMessage
  case object SetTerraPreference extends UserServiceMessage
  case object DeleteTerraPreference extends UserServiceMessage

  def props(userService: (UserInfo) => UserService, userInfo: UserInfo): Props = {
    Props(userService(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new UserService(app.rawlsDAO, app.thurloeDAO, userInfo)
}

class UserService(rawlsDAO: RawlsDAO, thurloeDAO: ThurloeDAO, userToken: UserInfo)(implicit protected val executionContext: ExecutionContext)
  extends Actor with LazyLogging with SprayJsonSupport with DefaultJsonProtocol {

  override def receive = {
    case ImportPermission => importPermission(userToken) pipeTo sender
    case GetTerraPreference => getTerraPreference(userToken) pipeTo sender
    case SetTerraPreference => setTerraPreference(userToken) pipeTo sender
    case DeleteTerraPreference => deleteTerraPreference(userToken) pipeTo sender
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
      hasProject <- billingProjects.map (_.exists(_.creationStatus == CreationStatuses.Ready))
      hasWorkspace <- workspaces.map { ws => ws.exists(_.accessLevel.compare(WorkspaceAccessLevels.Write) >= 0)}
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
    val futurePref:Future[TerraPreference] = thurloeDAO.getAllKVPs(userToken.id, userToken) map { // .getAllKVPs returns Option[ProfileWrapper]
      case None => TerraPreference(true, 0)
      case Some(wrapper) => {
        val pref:Boolean =  Try(getProfileValue(wrapper, UserService.TerraPreferenceKey).getOrElse("true").toBoolean)
          .toOption.getOrElse(true)
        val updated:Long = Try(getProfileValue(wrapper, UserService.TerraPreferenceLastUpdatedKey).getOrElse("0").toLong)
          .toOption.getOrElse(0L)
        TerraPreference(pref, updated)
      }
    }

    futurePref map { pref => RequestComplete(pref) }
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
}
