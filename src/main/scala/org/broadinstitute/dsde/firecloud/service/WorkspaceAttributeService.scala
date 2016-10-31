package org.broadinstitute.dsde.firecloud.service
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.service.WorkspaceAttributeService.{ExportWorkspaceAttributes, ImportAttributesFromTSV}
import org.broadinstitute.dsde.firecloud.utils.{RoleSupport, TSVLoadFile, TSVParser}
import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.model.Attributable._
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport
import spray.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import spray.json.DefaultJsonProtocol._
import spray.json.JsonParser.ParsingException
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.slf4j.LoggerFactory

object WorkspaceAttributeService {

  sealed trait WorkspaceAttributeMessage
  case class ExportWorkspaceAttributes(baseWorkspaceUrl: String, workspaceNamespace: String, workspaceName: String, filename: String) extends WorkspaceAttributeMessage
  case class ImportAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) extends WorkspaceAttributeMessage


  def props(WorkspaceAttributeService: UserInfo => WorkspaceAttributeService, userInfo: UserInfo): Props = {
    Props(WorkspaceAttributeService(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new WorkspaceAttributeService(userInfo, app.rawlsDAO)
}

class WorkspaceAttributeService(protected val argUserInfo: UserInfo, val rawlsDAO: RawlsDAO)(implicit protected val executionContext: ExecutionContext) extends Actor
  with LibraryServiceSupport with RoleSupport with SprayJsonSupport with LazyLogging {

  implicit val userInfo = argUserInfo

  lazy val log = LoggerFactory.getLogger(getClass)

  override def receive = {
    case ExportWorkspaceAttributes(baseWorkspaceUrl: String, workspaceNamespace: String, workspaceName: String, filename: String) =>
      { exportWorkspaceAttributes(baseWorkspaceUrl, workspaceNamespace, workspaceName, filename)} pipeTo sender
    case ImportAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) =>
      { importAttributesFromTSV(workspaceNamespace, workspaceName, tsvString)} pipeTo sender
  }

  def exportWorkspaceAttributes(baseWorkspaceUrl: String, workspaceNamespace: String, workspaceName: String, filename: String): Future[PerRequestMessage] = {
    Try(rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
      case Success(workspaceFuture) => workspaceFuture map { workspaceResponse =>
        val headerString = "workspace:" + (workspaceResponse.workspace.get.attributes map { attribute =>
            attribute._1.name}).mkString("\t").replaceAll("description\t", "")
        val valueString = (workspaceResponse.workspace.get.attributes map { attribute =>
          impAttributeFormat.write(attribute._2).toString().replaceAll("\"","")}).mkString("\t").replaceAll("null\t", "")
        RequestComplete(OK, headerString + "\n" + valueString)
      }
    }
  }

  def importAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String): Future[PerRequestMessage] = {
    Try(TSVParser.parse(tsvString)) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
      case Success(tsv) =>
        tsv.firstColumnHeader.split(":")(0)  match {
          case "workspace" =>
            importWorkspaceAttributeTSV(workspaceNamespace, workspaceName, tsv)
          case _ =>
            Future(RequestCompleteWithErrorReport(BadRequest, "Invalid first column header should start with \"workspace\""))
      }
    }
  }

  /*
   Import attributes on a workspace from a TSV file
  */
  private def importWorkspaceAttributeTSV(workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile): Future[PerRequestMessage] = {
    checkFirstRowDistinct(tsv) {
      val attributePairs = colNamesToWorkspaceAttributeNames(tsv.headers).zip(tsv.tsvData.head)
      Try(scala.util.parsing.json.JSONObject(attributePairs.toMap).toString().parseJson.asJsObject.convertTo[AttributeMap]) match {
        case Failure(ex:ParsingException) => Future(RequestCompleteWithErrorReport(BadRequest, "Invalid json supplied", ex))
        case Failure(e) => Future(RequestCompleteWithErrorReport(BadRequest, BadRequest.defaultMessage, e))
        case Success(attrs) =>
          rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
            val allOperations = getWorkspaceAttributeCalls(attributePairs)
            rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, allOperations) map (RequestComplete(_))
          }
      }
    }
  }

  private def getWorkspaceAttributeCalls(attributePairs: Seq[(String,String)]): Seq[AttributeUpdateOperation] = {
    attributePairs.map(pair =>
      if (pair._2.equals("__DELETE__")) {new RemoveAttribute(new AttributeName("default", pair._1))}
      else {new AddUpdateAttribute(new AttributeName("default", pair._1), new AttributeString(pair._2))}
    )
  }

  private def colNamesToWorkspaceAttributeNames(headers: Seq[String]): Seq[String] = {
      val newHead = headers.head.stripPrefix("workspace:")
      Seq(newHead) ++ headers.tail
  }

  private def checkFirstRowDistinct(tsv: TSVLoadFile)(op: => Future[PerRequestMessage]): Future[PerRequestMessage] = {
    val attributeNames =  colNamesToWorkspaceAttributeNames(tsv.headers)
    val distinctAttributes = attributeNames.distinct
    if (attributeNames.size != distinctAttributes.size) {
        Future(RequestCompleteWithErrorReport(BadRequest,
            "Duplicated attribute keys are not allowed"))
    } else {
        op
    }
  }

}
