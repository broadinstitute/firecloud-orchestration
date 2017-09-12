package org.broadinstitute.dsde.firecloud.service

import akka.actor._
import akka.pattern.pipe
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, _}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.utils.{PermissionsSupport, TSVFormatter, TSVLoadFile}
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, AttributeUpdateOperation, RemoveListMember}
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.rawls.model._
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.http.{HttpHeaders, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Created by mbemis on 10/19/16.
 */
object WorkspaceService {
  sealed trait WorkspaceServiceMessage
  case class GetCatalog(workspaceNamespace: String, workspaceName: String, userInfo: UserInfo) extends WorkspaceServiceMessage
  case class UpdateCatalog(workspaceNamespace: String, workspaceName: String, updates: Seq[WorkspaceCatalog], userInfo: UserInfo) extends WorkspaceServiceMessage
  case class GetStorageCostEstimate(workspaceNamespace: String, workspaceName: String) extends WorkspaceServiceMessage
  case class UpdateWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, workspaceUpdateJson: Seq[AttributeUpdateOperation]) extends WorkspaceServiceMessage
  case class SetWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) extends WorkspaceServiceMessage
  case class UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String, inviteUsersNotFound: Boolean) extends WorkspaceServiceMessage
  case class ExportWorkspaceAttributesTSV(workspaceNamespace: String, workspaceName: String, filename: String) extends WorkspaceServiceMessage
  case class ImportAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) extends WorkspaceServiceMessage
  case class GetTags(workspaceNamespace: String, workspaceName: String) extends WorkspaceServiceMessage
  case class PutTags(workspaceNamespace: String, workspaceName: String, tags: List[String]) extends WorkspaceServiceMessage
  case class PatchTags(workspaceNamespace: String, workspaceName: String, tags: List[String]) extends WorkspaceServiceMessage
  case class DeleteTags(workspaceNamespace: String, workspaceName: String, tags: List[String]) extends WorkspaceServiceMessage
  case class DeleteWorkspace(workspaceNamespace: String, workspaceName: String) extends WorkspaceServiceMessage

  def props(workspaceServiceConstructor: WithAccessToken => WorkspaceService, userToken: WithAccessToken): Props = {
    Props(workspaceServiceConstructor(userToken))
  }

  def constructor(app: Application)(userToken: WithAccessToken)(implicit executionContext: ExecutionContext) =
    new WorkspaceService(userToken, app.rawlsDAO, app.thurloeDAO, app.googleServicesDAO, app.ontologyDAO, app.searchDAO)
}

class WorkspaceService(protected val argUserToken: WithAccessToken, val rawlsDAO: RawlsDAO, val thurloeDAO: ThurloeDAO, val googleServicesDAO: GoogleServicesDAO, val ontologyDAO: OntologyDAO, val searchDAO: SearchDAO)
                      (implicit protected val executionContext: ExecutionContext) extends Actor with AttributeSupport with TSVFileSupport with PermissionsSupport with WorkspacePublishingSupport {

  implicit val system = context.system

  val log = Logging(system, getClass)

  implicit val userToken = argUserToken

  import WorkspaceService._

  override def receive: Receive = {

    case GetCatalog(workspaceNamespace: String, workspaceName: String, userInfo: UserInfo) =>
      getCatalog(workspaceNamespace, workspaceName, userInfo) pipeTo sender
    case UpdateCatalog(workspaceNamespace: String, workspaceName: String, updates: Seq[WorkspaceCatalog], userInfo: UserInfo) =>
      updateCatalog(workspaceNamespace, workspaceName, updates, userInfo) pipeTo sender
    case GetStorageCostEstimate(workspaceNamespace: String, workspaceName: String) =>
      getStorageCostEstimate(workspaceNamespace, workspaceName) pipeTo sender
    case UpdateWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, workspaceUpdateJson: Seq[AttributeUpdateOperation]) =>
      updateWorkspaceAttributes(workspaceNamespace, workspaceName, workspaceUpdateJson) pipeTo sender
    case SetWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) =>
      setWorkspaceAttributes(workspaceNamespace, workspaceName, newAttributes) pipeTo sender
    case UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String, inviteUsersNotFound: Boolean) =>
      updateWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, originEmail, inviteUsersNotFound) pipeTo sender
    case ExportWorkspaceAttributesTSV(workspaceNamespace: String, workspaceName: String, filename: String) =>
      exportWorkspaceAttributesTSV(workspaceNamespace, workspaceName, filename) pipeTo sender
    case ImportAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) =>
      importAttributesFromTSV(workspaceNamespace, workspaceName, tsvString) pipeTo sender
    case GetTags(workspaceNamespace: String, workspaceName: String) =>
      getTags(workspaceNamespace, workspaceName) pipeTo sender
    case PutTags(workspaceNamespace: String, workspaceName: String,tags:List[String]) =>
      putTags(workspaceNamespace, workspaceName, tags) pipeTo sender
    case PatchTags(workspaceNamespace: String, workspaceName: String,tags:List[String]) =>
      patchTags(workspaceNamespace, workspaceName, tags) pipeTo sender
    case DeleteTags(workspaceNamespace: String, workspaceName: String,tags:List[String]) =>
      deleteTags(workspaceNamespace, workspaceName, tags) pipeTo sender
    case DeleteWorkspace(workspaceNamespace: String, workspaceName: String) =>
      deleteWorkspace(workspaceNamespace, workspaceName) pipeTo sender

  }

  def getStorageCostEstimate(workspaceNamespace: String, workspaceName: String): Future[RequestComplete[WorkspaceStorageCostEstimate]] = {
    rawlsDAO.getBucketUsage(workspaceNamespace, workspaceName).zip(googleServicesDAO.fetchPriceList) map {
      case (usage, priceList) =>
        val rate = priceList.prices.cpBigstoreStorage.us
        val estimate: BigDecimal = BigDecimal(usage.usageInBytes) / 1000000000 * rate
        RequestComplete(WorkspaceStorageCostEstimate(f"$$$estimate%.2f"))
    }
  }

  def updateWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, workspaceUpdateJson: Seq[AttributeUpdateOperation]) = {
    rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, workspaceUpdateJson) map { ws =>
      republishDocument(ws, ontologyDAO, searchDAO)
      RequestComplete(ws)
    }
  }

  def setWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
      // this is technically vulnerable to a race condition in which the workspace attributes have changed
      // between the time we retrieved them and here, where we update them.
      val allOperations = generateAttributeOperations(workspaceResponse.workspace.attributes, newAttributes, _.namespace != AttributeName.libraryNamespace)
      rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, allOperations) map { ws =>
        republishDocument(ws, ontologyDAO, searchDAO)
        RequestComplete(ws)
      }
    }
  }

  def getCatalog(workspaceNamespace: String, workspaceName: String, userInfo: UserInfo): Future[PerRequestMessage] = {
    asPermitted(workspaceNamespace, workspaceName, WorkspaceAccessLevels.Read, userInfo) {
      rawlsDAO.getCatalog(workspaceNamespace, workspaceName) map (RequestComplete(_))
    }
  }

  def updateCatalog(workspaceNamespace: String, workspaceName: String, updates: Seq[WorkspaceCatalog], userInfo: UserInfo): Future[PerRequestMessage] = {
    // can update if admin or owner of workspace
    asPermitted(workspaceNamespace, workspaceName, WorkspaceAccessLevels.Owner, userInfo) {
      rawlsDAO.patchCatalog(workspaceNamespace, workspaceName, updates) map (RequestComplete(_))
    }
  }

  def updateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String, inviteUsersNotFound: Boolean) = {
    val aclUpdate = rawlsDAO.patchWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, inviteUsersNotFound)
    aclUpdate map { actualUpdates =>
      RequestComplete(actualUpdates)
    }
  }

  def exportWorkspaceAttributesTSV(workspaceNamespace: String, workspaceName: String, filename: String): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) map { workspaceResponse =>
      val attributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer
      val attributes = workspaceResponse.workspace.attributes.filterKeys(_ != AttributeName.withDefaultNS("description"))
      val headerString = "workspace:" + (attributes map { case (attName, attValue) => attName.name }).mkString("\t")
      val valueString = (attributes map { case (attName, attValue) => TSVFormatter.cleanValue(attributeFormat.write(attValue)) }).mkString("\t")
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

  def getTags(workspaceNamespace: String, workspaceName: String): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
      val tags = getTagsFromWorkspace(workspaceResponse.workspace)
      Future(RequestComplete(StatusCodes.OK, formatTags(tags)))
    }
  }

  def putTags(workspaceNamespace: String, workspaceName: String, tags: List[String]): Future[PerRequestMessage] = {
    val attrList = AttributeValueList(tags map (tag => AttributeString(tag.trim)))
    val op = AddUpdateAttribute(AttributeName.withTagsNS, attrList)
    rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, Seq(op)) flatMap { ws =>
      republishDocument(ws, ontologyDAO, searchDAO)

      val tags = getTagsFromWorkspace(ws)
      Future(RequestComplete(StatusCodes.OK, formatTags(tags)))
    }
  }

  def patchTags(workspaceNamespace: String, workspaceName: String, tags: List[String]): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { origWs =>
      val origTags = getTagsFromWorkspace(origWs.workspace)
      val attrOps = (tags diff origTags) map (tag => AddListMember(AttributeName.withTagsNS, AttributeString(tag.trim)))
      rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, attrOps) flatMap { patchedWs =>
        republishDocument(patchedWs, ontologyDAO, searchDAO)

        val tags = getTagsFromWorkspace(patchedWs)
        Future(RequestComplete(StatusCodes.OK, formatTags(tags)))
      }
    }
  }

  def deleteTags(workspaceNamespace: String, workspaceName: String, tags: List[String]): Future[PerRequestMessage] = {
    val attrOps = tags map (tag => RemoveListMember(AttributeName.withTagsNS, AttributeString(tag.trim)))
    rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, attrOps) flatMap { ws =>
      republishDocument(ws, ontologyDAO, searchDAO)

      val tags = getTagsFromWorkspace(ws)
      Future(RequestComplete(StatusCodes.OK, formatTags(tags)))
    }

  }

  def unPublishSuccessMessage(workspaceNamespace: String, workspaceName: String): String = s" The workspace $workspaceNamespace:$workspaceName has been un-published."

  def deleteWorkspace(ns: String, name: String): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(ns, name) flatMap { ws =>
      val wsPublished: Boolean = isPublished(ws)
      if (wsPublished) {
        rawlsDAO.updateLibraryAttributes(ns, name, updatePublishAttribute(false)) flatMap { f1 =>
          Future(removeDocument(ws.workspace, searchDAO)).flatMap { f2 =>
            rawlsDAO.deleteWorkspace(ns, name) map { f3 =>
              RequestComplete(f3.copy(message = Some(f3.message.getOrElse("") + unPublishSuccessMessage(ns, name))))
            }
          }
        } recover {
          case e: FireCloudExceptionWithErrorReport => RequestComplete(e.errorReport.statusCode.getOrElse(InternalServerError), e.errorReport)
          case e => RequestComplete(InternalServerError, ErrorReport(message = e.getMessage))
        }
      } else {
        rawlsDAO.deleteWorkspace(ws.workspace.namespace, ws.workspace.name) map { response =>
          RequestComplete(response)
        }
      }
    }
  }

  private def getTagsFromWorkspace(ws:Workspace): Seq[String] = {
    ws.attributes.get(AttributeName.withTagsNS) match {
      case Some(vals:AttributeValueList) => vals.list collect {
        case s:AttributeString => s.value
      }
      case _ => Seq.empty[String]
    }
  }

  private def formatTags(tags: Seq[String]) = tags.toList.sortBy(_.toLowerCase)


}
