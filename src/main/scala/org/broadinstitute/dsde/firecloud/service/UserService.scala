package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impUserImportPermission
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, RequestCompleteWithErrorReport, UserImportPermission, UserInfo}
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

  val TerraPreferenceKey = "PreferTerra"

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

  def getTerraPreference(implicit userToken: UserInfo): Future[PerRequestMessage] = {
    // so, so many nested Options ...
    val maybeValue:Future[Option[Long]] = thurloeDAO.getAllKVPs(userToken.id, userToken) map { // .getAllKVPs returns Option[ProfileWrapper]
      case None => None
      case Some(wrapper) => wrapper.keyValuePairs
        .find(_.key.contains(UserService.TerraPreferenceKey)) // .find returns Option[FireCloudKeyValue]
        .flatMap(_.value.map(_.toLong)) // .value returns Option[String]
    }
    maybeValue map { v =>
      val pref:Boolean = v.getOrElse(0L) >= 0 // if user no key, or the key has no value, default to 0
      RequestComplete(Map(UserService.TerraPreferenceKey -> pref))
    }
  }

  def setTerraPreference(userToken: UserInfo): Future[PerRequestMessage] = {
    writeTerraPreference(userToken, System.currentTimeMillis())
  }


  def deleteTerraPreference(userToken: UserInfo): Future[PerRequestMessage] = {
    writeTerraPreference(userToken, System.currentTimeMillis() * -1)
  }

  private def writeTerraPreference(userToken: UserInfo, prefValue: Long): Future[PerRequestMessage] = {
    thurloeDAO.saveKeyValues(userToken, Map(UserService.TerraPreferenceKey -> prefValue.toString)) flatMap {
      case Failure(exception) => Future(RequestCompleteWithErrorReport(StatusCodes.InternalServerError,
        "could not save preference", exception))
      case Success(_) => getTerraPreference(userToken)
    }
  }
}
