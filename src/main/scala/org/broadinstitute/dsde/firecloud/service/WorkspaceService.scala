package org.broadinstitute.dsde.firecloud.service
import WorkspaceService.{ExportWorkspaceAttributes, ImportAttributesFromTSV, UpdateWorkspaceACL}
import akka.actor._
import akka.pattern._
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.{TSVLoadFile, TSVParser}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport}
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.JsonParser.ParsingException
import scala.util.{Failure, Success, Try}
import scala.concurrent.{ExecutionContext, Future}



object WorkspaceService {
  sealed trait WorkspaceServiceMessage
  case class UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) extends WorkspaceServiceMessage
  case class ExportWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, filename: String) extends WorkspaceServiceMessage
  case class ImportAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) extends WorkspaceServiceMessage

  def props(workspaceServiceConstructor: UserInfo => WorkspaceService, userInfo: UserInfo): Props = {
    Props(workspaceServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new WorkspaceService(userInfo, app.rawlsDAO, app.thurloeDAO)
}

class WorkspaceService(protected val argUserInfo: UserInfo, val rawlsDAO: RawlsDAO, val thurloeDAO: ThurloeDAO) extends Actor {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  implicit val userInfo = argUserInfo

  override def receive: Receive = {

    case UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) =>
      updateWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, originEmail) pipeTo sender
    case ExportWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, filename: String) =>
      exportWorkspaceAttributes(workspaceNamespace, workspaceName, filename) pipeTo sender
    case ImportAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) =>
      importAttributesFromTSV(workspaceNamespace, workspaceName, tsvString) pipeTo sender

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

  def exportWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, filename: String): Future[PerRequestMessage] = {
    Try(rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest, regret.getMessage))
      case Success(workspaceFuture) => workspaceFuture map { workspaceResponse =>
          val headerString = "workspace:" + (workspaceResponse.workspace.get.attributes map { case (attName, attValue) =>
                attName.name}).mkString("\t").replaceAll("description\t", "")
          val valueString = (workspaceResponse.workspace.get.attributes map { attribute =>
              impAttributeFormat.write(attribute._2).toString().replaceAll("\"","")}).mkString("\t").replaceAll("null\t", "")
          RequestComplete(StatusCodes.OK, headerString + "\n" + valueString)
        }
    }
  }

  def importAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String): Future[PerRequestMessage] = {
    Try(TSVParser.parse(tsvString)) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest, regret.getMessage))
      case Success(tsv) =>
          tsv.firstColumnHeader.split(":")(0)  match {
            case "workspace" =>
                importWorkspaceAttributeTSV(workspaceNamespace, workspaceName, tsv)
            case _ =>
                Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "Invalid first column header should start with \"workspace\""))
        }
    }
  }

   /*
  Import attributes on a workspace from a TSV file
  */
  private def importWorkspaceAttributeTSV(workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile): Future[PerRequestMessage] = {
    checkIf2Rows(tsv) {
      checkFirstRowDistinct(tsv) {
        val attributePairs = colNamesToWorkspaceAttributeNames(tsv.headers).zip(tsv.tsvData.head)
        Try(scala.util.parsing.json.JSONObject(attributePairs.toMap).toString().parseJson.asJsObject.convertTo[AttributeMap]) match {
          case Failure(ex: ParsingException) => Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "Invalid json supplied", ex))
          case Failure(e) => Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest, StatusCodes.BadRequest.defaultMessage, e))
          case Success(attrs) =>
            rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
              val allOperations = getWorkspaceAttributeCalls(attributePairs)
              rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, allOperations) map (RequestComplete(_))
            }
        }
      }
    }
  }

  private def getWorkspaceAttributeCalls(attributePairs: Seq[(String,String)]): Seq[AttributeUpdateOperation] = {
    attributePairs.map { case (name, value) =>
      if (value.equals("__DELETE__"))
        new RemoveAttribute(new AttributeName("default", name))
      else
        new AddUpdateAttribute(new AttributeName("default", name), new AttributeString(value))
    }
  }

  private def colNamesToWorkspaceAttributeNames(headers: Seq[String]): Seq[String] = {
    val newHead = headers.head.stripPrefix("workspace:")
    Seq(newHead) ++ headers.tail
  }

  private def checkIf2Rows(tsv: TSVLoadFile)(op: => Future[PerRequestMessage]): Future[PerRequestMessage] = {
    if (tsv.tsvData.length != 1) {
      Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest,
        "Your file does not have the correct number of rows. There should be 2."))
    } else {
      op
    }
  }

  private def checkFirstRowDistinct(tsv: TSVLoadFile)(op: => Future[PerRequestMessage]): Future[PerRequestMessage] = {
    val attributeNames =  colNamesToWorkspaceAttributeNames(tsv.headers)
    val distinctAttributes = attributeNames.distinct
    if (attributeNames.size != distinctAttributes.size) {
      Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "Duplicated attribute keys are not allowed"))
    } else {
        op
    }
  }

}