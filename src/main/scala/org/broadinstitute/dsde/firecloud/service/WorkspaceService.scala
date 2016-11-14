package org.broadinstitute.dsde.firecloud.service

import WorkspaceService.{ExportWorkspaceAttributesTSV, ImportAttributesFromTSV, UpdateWorkspaceACL}
import akka.actor._
import akka.pattern._
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import spray.http.StatusCodes
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.utils.{TSVLoadFile, TSVParser}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.RequestCompleteWithErrorReport
import spray.http.MediaTypes._
import spray.http.{HttpHeaders, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.JsonParser.ParsingException
import scala.util.{Failure, Success, Try}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by mbemis on 10/19/16.
 */
object WorkspaceService {
  sealed trait WorkspaceServiceMessage
  case class SetWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) extends WorkspaceServiceMessage
  case class UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) extends WorkspaceServiceMessage
  case class ExportWorkspaceAttributesTSV(workspaceNamespace: String, workspaceName: String, filename: String) extends WorkspaceServiceMessage
  case class ImportAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) extends WorkspaceServiceMessage

  def props(workspaceServiceConstructor: WithAccessToken => WorkspaceService, userInfo: WithAccessToken): Props = {
    Props(workspaceServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: WithAccessToken)(implicit executionContext: ExecutionContext) =
    new WorkspaceService(userInfo, app.rawlsDAO, app.thurloeDAO)
}

class WorkspaceService(protected val argUserInfo: WithAccessToken, val rawlsDAO: RawlsDAO, val thurloeDAO: ThurloeDAO) extends Actor with AttributeSupport {

  implicit val system = context.system

  import system.dispatcher

  val log = Logging(system, getClass)

  implicit val userInfo = argUserInfo

  import WorkspaceService._

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
      val allOperations = generateAttributeOperations(workspaceResponse.workspace.get.attributes, newAttributes, _.namespace != AttributeName.libraryNamespace)
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
      val valueString = (attributes map { case (attName, attValue) => impAttributeFormat.write(attValue) }).mkString("\t")
      RequestCompleteWithHeaders((StatusCodes.OK, headerString + "\n" + valueString),
        HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> filename)),
        HttpHeaders.`Content-Type`(`text/plain`))
    }
  }

  def importAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String): Future[PerRequestMessage] = {
    Try(TSVParser.parse(tsvString)) match {
      case Failure(regret) => Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, regret.getMessage))
      case Success(tsv) =>
        tsv.firstColumnHeader.split(":")(0) match {
          case "workspace" =>
            importWorkspaceAttributeTSV(workspaceNamespace, workspaceName, tsv)
          case _ =>
            Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "Invalid first column header should start with \"workspace\""))
        }
    }
  }

  private def importWorkspaceAttributeTSV(workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile): Future[PerRequestMessage] = {
    if (tsv.tsvData.length != 1) {
      Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "Your file does not have the correct number of rows. There should be 2."))
    }
    val attributeNames = Seq(tsv.headers.head.stripPrefix("workspace:")) ++ tsv.headers.tail
    val distinctAttributes = attributeNames.distinct
    if (attributeNames.size != distinctAttributes.size) {
      Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "Duplicated attribute keys are not allowed"))
    }
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
      val allOperations = getWorkspaceAttributeCalls(attributeNames.zip(tsv.tsvData.head))
      rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, allOperations) map (RequestComplete(_))
    }
  }

  private def getWorkspaceAttributeCalls(attributePairs: Seq[(String,String)]): Seq[AttributeUpdateOperation] = {
    attributePairs.map { case (name, value) =>
      if (value.equals("__DELETE__"))
        new RemoveAttribute(new AttributeName("default", name))
      else {
        log.info(value.parseJson.toString())
        new AddUpdateAttribute(new AttributeName("default", name), impAttributeFormat.read(value.parseJson))
      }
    }
  }

}
