package org.broadinstitute.dsde.firecloud.service

import akka.actor._
import akka.pattern._
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.RequestCompleteWithErrorReport
import spray.http.MediaTypes._
import spray.http.{HttpHeaders, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import scala.util.{Failure, Success, Try}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Created by mbemis on 10/19/16.
 */
object WorkspaceService {
  sealed trait WorkspaceServiceMessage
  case class SetWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) extends WorkspaceServiceMessage
  case class UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) extends WorkspaceServiceMessage
  case class ExportWorkspaceAttributesTSV(workspaceNamespace: String, workspaceName: String, filename: String) extends WorkspaceServiceMessage
  case class ImportAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) extends WorkspaceServiceMessage


  def props(workspaceServiceConstructor: WithAccessToken => WorkspaceService, userToken: WithAccessToken): Props = {
    Props(workspaceServiceConstructor(userToken))
  }

  def constructor(app: Application)(userToken: WithAccessToken)(implicit executionContext: ExecutionContext) =
    new WorkspaceService(userToken, app.rawlsDAO, app.thurloeDAO)
}

class WorkspaceService(protected val argUserToken: WithAccessToken, val rawlsDAO: RawlsDAO, val thurloeDAO: ThurloeDAO) extends Actor with AttributeSupport with TSVFileSupport {

  implicit val system = context.system

  import system.dispatcher

  val log = Logging(system, getClass)

  implicit val userToken = argUserToken

  import WorkspaceService._

  private implicit val impPlainAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer

  override def receive: Receive = {

    case SetWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) =>
      setWorkspaceAttributes(workspaceNamespace, workspaceName, newAttributes) pipeTo sender
    case UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) =>
      updateWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, originEmail) pipeTo sender
    case ExportWorkspaceAttributesTSV(workspaceNamespace: String, workspaceName: String, filename: String) =>
      exportWorkspaceAttributesTSV(workspaceNamespace, workspaceName, filename) pipeTo sender
    case ImportAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) =>
      importAttributesFromTSV(workspaceNamespace, workspaceName, tsvString) pipeTo sender


  }

  def setWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
      // this is technically vulnerable to a race condition in which the workspace attributes have changed
      // between the time we retrieved them and here, where we update them.
      val allOperations = generateAttributeOperations(workspaceResponse.workspace.attributes, newAttributes, _.namespace != AttributeName.libraryNamespace)
      rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, allOperations) map (RequestComplete(_))
    }
  }

  def updateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) = {

    val aclUpdate = rawlsDAO.patchWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates)
    aclUpdate map { actualUpdates =>

      val allNotifications = actualUpdates.map {
        case removed if removed.accessLevel.equals(WorkspaceAccessLevels.NoAccess) => WorkspaceRemovedNotification(removed.email, removed.accessLevel.toString, workspaceNamespace, workspaceName, originEmail)
        case added => WorkspaceAddedNotification(added.email, added.accessLevel.toString, workspaceNamespace, workspaceName, originEmail)
      }

      thurloeDAO.sendNotifications(allNotifications)

      RequestComplete(actualUpdates)
    }
  }


  def exportWorkspaceAttributesTSV(workspaceNamespace: String, workspaceName: String, filename: String): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) map { workspaceResponse =>
      val attributes = workspaceResponse.workspace.get.attributes.filterKeys(_ != AttributeName("default", "description"))
      val headerString = "workspace:" + (attributes map { case (attName, attValue) => attName.name }).mkString("\t")
      val valueString = (attributes map { case (attName, attValue) => TSVFormatter.cleanValue(impPlainAttributeFormat.write(attValue)) }).mkString("\t")
      RequestCompleteWithHeaders((StatusCodes.OK, headerString + "\n" + valueString),
        HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> filename)),
        HttpHeaders.`Content-Type`(`text/plain`))
    }
  }

  def importAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String): Future[PerRequestMessage] = {
    withTSVFile(tsvString) { tsv =>
      tsv.firstColumnHeader.split(":")(0) match {
        case "workspace" =>
          importWorkspaceAttributeTSV(workspaceNamespace, workspaceName, tsv)
        case _ =>
          Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "Invalid TSV. First column header should start with \"workspace\""))
      }
    }
  }

  private def importWorkspaceAttributeTSV(workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile): Future[PerRequestMessage] = {
    checkNumberOfRows(tsv, 2) {
      checkFirstRowDistinct(tsv) {
        rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
          Try(getWorkspaceAttributeCalls(tsv)) match {
            case Failure(regret) => Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest,
              "One or more of your values are not in the correct format"))
            case Success(attributeCalls) => rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, attributeCalls) map (RequestComplete(_))
          }
        }
      }
    }
  }

}