package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ShareLog.ShareType
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, _}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.utils.{PermissionsSupport, TSVFormatter, TSVLoadFile}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, AttributeUpdateOperation, RemoveListMember}
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * Created by mbemis on 10/19/16.
  */
object WorkspaceService {
  def constructor(app: Application)(userToken: WithAccessToken)(implicit executionContext: ExecutionContext) =
    new WorkspaceService(userToken, app.rawlsDAO, app.samDAO, app.thurloeDAO, app.googleServicesDAO, app.ontologyDAO, app.searchDAO, app.consentDAO, app.shareLogDAO)
}

class WorkspaceService(protected val argUserToken: WithAccessToken, val rawlsDAO: RawlsDAO, val samDao: SamDAO, val thurloeDAO: ThurloeDAO, val googleServicesDAO: GoogleServicesDAO, val ontologyDAO: OntologyDAO, val searchDAO: SearchDAO, val consentDAO: ConsentDAO, val shareLogDAO: ShareLogDAO)
                      (implicit protected val executionContext: ExecutionContext) extends AttributeSupport with TSVFileSupport with PermissionsSupport with WorkspacePublishingSupport with SprayJsonSupport with LazyLogging {

  implicit val userToken = argUserToken

  def GetCatalog(workspaceNamespace: String, workspaceName: String, userInfo: UserInfo) = getCatalog(workspaceNamespace, workspaceName, userInfo)
  def UpdateCatalog(workspaceNamespace: String, workspaceName: String, updates: Seq[WorkspaceCatalog], userInfo: UserInfo) = updateCatalog(workspaceNamespace, workspaceName, updates, userInfo)
  def GetStorageCostEstimate(workspaceNamespace: String, workspaceName: String) = getStorageCostEstimate(workspaceNamespace, workspaceName)
  def UpdateWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, workspaceUpdateJson: Seq[AttributeUpdateOperation]) = updateWorkspaceAttributes(workspaceNamespace, workspaceName, workspaceUpdateJson)
  def SetWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) = setWorkspaceAttributes(workspaceNamespace, workspaceName, newAttributes)
  def UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String, originId: String, inviteUsersNotFound: Boolean) = updateWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, originEmail, originId, inviteUsersNotFound)
  def ExportWorkspaceAttributesTSV(workspaceNamespace: String, workspaceName: String, filename: String) = exportWorkspaceAttributesTSV(workspaceNamespace, workspaceName, filename)
  def ImportAttributesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) = importAttributesFromTSV(workspaceNamespace, workspaceName, tsvString)
  def GetTags(workspaceNamespace: String, workspaceName: String) = getTags(workspaceNamespace, workspaceName)
  def PutTags(workspaceNamespace: String, workspaceName: String,tags:List[String]) = putTags(workspaceNamespace, workspaceName, tags)
  def PatchTags(workspaceNamespace: String, workspaceName: String,tags:List[String]) = patchTags(workspaceNamespace, workspaceName, tags)
  def DeleteTags(workspaceNamespace: String, workspaceName: String,tags:List[String]) = deleteTags(workspaceNamespace, workspaceName, tags)
  def DeleteWorkspace(workspaceNamespace: String, workspaceName: String) = deleteWorkspace(workspaceNamespace, workspaceName)
  def CloneWorkspace(workspaceNamespace: String, workspaceName: String, cloneRequest: WorkspaceRequest) = cloneWorkspace(workspaceNamespace, workspaceName, cloneRequest)

  def getStorageCostEstimate(workspaceNamespace: String, workspaceName: String): Future[RequestComplete[WorkspaceStorageCostEstimate]] = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
      samDao.getPetServiceAccountKeyForUser(userToken, GoogleProject(workspaceNamespace)) flatMap { petKey =>
        googleServicesDAO.getBucket(workspaceResponse.workspace.bucketName, petKey) match {
          case Some(bucket) =>
            rawlsDAO.getBucketUsage(workspaceNamespace, workspaceName).zip(googleServicesDAO.fetchPriceList) map {
             case (usage, priceList) =>
                val rate = priceList.prices.cpBigstoreStorage.getOrElse(bucket.getLocation.toLowerCase(), priceList.prices.cpBigstoreStorage("us"))
                // Convert bytes to GB since rate is based on GB.
                val estimate: BigDecimal = BigDecimal(usage.usageInBytes) / (1024 * 1024 * 1024) * rate
                RequestComplete(WorkspaceStorageCostEstimate(f"$$$estimate%.2f"))
            }
          case None => throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Unable to fetch bucket to calculate storage cost"))
        }
      }
    }
  }

  def updateWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, workspaceUpdateJson: Seq[AttributeUpdateOperation]) = {
    rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, workspaceUpdateJson) map { ws =>
      republishDocument(ws, ontologyDAO, searchDAO, consentDAO)
      RequestComplete(ws)
    }
  }

  def setWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
      // this is technically vulnerable to a race condition in which the workspace attributes have changed
      // between the time we retrieved them and here, where we update them.
      val allOperations = generateAttributeOperations(workspaceResponse.workspace.attributes.getOrElse(Map.empty), newAttributes, _.namespace != AttributeName.libraryNamespace)
      rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, allOperations) map { ws =>
        republishDocument(ws, ontologyDAO, searchDAO, consentDAO)
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

  def updateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String, originId: String, inviteUsersNotFound: Boolean): Future[RequestComplete[WorkspaceACLUpdateResponseList]] = {
    def logShares(aclUpdateList: WorkspaceACLUpdateResponseList) = {
      // this will log a share every time a workspace is shared with a user
      // it will also log a share every time a workspace permission is changed
      // i.e. READER to WRITER, etc
      val sharees = aclUpdateList.usersUpdated.filterNot(_.accessLevel == WorkspaceAccessLevels.NoAccess).map(_.email)
      val invitesSent = aclUpdateList.invitesSent.map(_.email)
      shareLogDAO.logShares(originId, (sharees ++ invitesSent).toSeq, ShareType.WORKSPACE)
    }

    val aclUpdate = rawlsDAO.patchWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, inviteUsersNotFound)

    aclUpdate map { actualUpdates =>
      logShares(actualUpdates)
      RequestComplete(actualUpdates)
    }
  }

  def exportWorkspaceAttributesTSV(workspaceNamespace: String, workspaceName: String, filename: String): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) map { workspaceResponse =>
      val attributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer
      val attributes = workspaceResponse.workspace.attributes.getOrElse(Map.empty).filterKeys(_ != AttributeName.withDefaultNS("description"))
      val headerString = "workspace:" + (attributes map { case (attName, attValue) => attName.name }).mkString("\t")
      val valueString = (attributes map { case (attName, attValue) => TSVFormatter.cleanValue(attributeFormat.write(attValue)) }).mkString("\t")
      // TODO: entity TSVs are downloaded as text/tab-separated-value, but workspace attributes are text/plain. Align these?
      RequestCompleteWithHeaders((StatusCodes.OK, headerString + "\n" + valueString),
        `Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> filename)),
        `Content-Type`(ContentTypes.`text/plain(UTF-8)`))
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
      republishDocument(ws, ontologyDAO, searchDAO, consentDAO)

      val tags = getTagsFromWorkspace(ws)
      Future(RequestComplete(StatusCodes.OK, formatTags(tags)))
    }
  }

  def patchTags(workspaceNamespace: String, workspaceName: String, tags: List[String]): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { origWs =>
      val origTags = getTagsFromWorkspace(origWs.workspace)
      val attrOps = (tags diff origTags) map (tag => AddListMember(AttributeName.withTagsNS, AttributeString(tag.trim)))
      rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, attrOps) flatMap { patchedWs =>
        republishDocument(patchedWs, ontologyDAO, searchDAO, consentDAO)

        val tags = getTagsFromWorkspace(patchedWs)
        Future(RequestComplete(StatusCodes.OK, formatTags(tags)))
      }
    }
  }

  def deleteTags(workspaceNamespace: String, workspaceName: String, tags: List[String]): Future[PerRequestMessage] = {
    val attrOps = tags map (tag => RemoveListMember(AttributeName.withTagsNS, AttributeString(tag.trim)))
    rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, attrOps) flatMap { ws =>
      republishDocument(ws, ontologyDAO, searchDAO, consentDAO)

      val tags = getTagsFromWorkspace(ws)
      Future(RequestComplete(StatusCodes.OK, formatTags(tags)))
    }

  }

  def unPublishSuccessMessage(workspaceNamespace: String, workspaceName: String): String = s" The workspace $workspaceNamespace:$workspaceName has been un-published."

  def deleteWorkspace(ns: String, name: String): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(ns, name) flatMap { wsResponse =>
      val unpublishFuture: Future[WorkspaceDetails] = if (isPublished(wsResponse))
        setWorkspacePublishedStatus(wsResponse.workspace, publishArg = false, rawlsDAO, ontologyDAO, searchDAO, consentDAO)
      else
        Future.successful(wsResponse.workspace)
      unpublishFuture flatMap { ws =>
        rawlsDAO.deleteWorkspace(ns, name) map { wsResponse =>
          RequestComplete(wsResponse.copy(message = Some(wsResponse.message.getOrElse("") + unPublishSuccessMessage(ns, name))))
        }
      } recover {
        case e: FireCloudExceptionWithErrorReport => RequestComplete(e.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError), ErrorReport(message = s"You cannot delete this workspace: ${e.errorReport.message}"))
        case e: Throwable => RequestComplete(StatusCodes.InternalServerError, ErrorReport(message = s"You cannot delete this workspace: ${e.getMessage}"))
      }
    }
  }

  def cloneWorkspace(namespace: String, name: String, cloneRequest: WorkspaceRequest): Future[PerRequestMessage] = {
    rawlsDAO.cloneWorkspace(namespace, name, cloneRequest).map { res =>
      RequestComplete(StatusCodes.Created, res)
    }
  }

  private def getTagsFromWorkspace(ws:WorkspaceDetails): Seq[String] = {
    ws.attributes.getOrElse(Map.empty).get(AttributeName.withTagsNS) match {
      case Some(vals:AttributeValueList) => vals.list collect {
        case s:AttributeString => s.value
      }
      case _ => Seq.empty[String]
    }
  }

  private def formatTags(tags: Seq[String]) = tags.toList.sortBy(_.toLowerCase)


}
