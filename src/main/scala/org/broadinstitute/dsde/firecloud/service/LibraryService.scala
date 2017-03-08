package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException}
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, SearchDAO}
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.LibraryService._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.RoleSupport
import org.everit.json.schema.ValidationException
import org.slf4j.LoggerFactory
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport
import spray.json.JsonParser.ParsingException
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impLibraryBulkIndexResponse, impLibrarySearchResponse}
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.{AttributeNameFormat, WorkspaceFormat}
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddListMember, AttributeUpdateOperation, RemoveAttribute}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object LibraryService {
  final val publishedFlag = AttributeName("library","published")
  final val discoverableWSAttribute = AttributeName("library","discoverableByGroups")
  final val schemaLocation = "library/attribute-definitions.json"

  sealed trait LibraryServiceMessage
  case class UpdateAttributes(ns: String, name: String, attrsJsonString: String) extends LibraryServiceMessage
  case class UpdateDiscoverableByGroups(ns: String, name: String, newGroups: Seq[String]) extends LibraryServiceMessage
  case class SetPublishAttribute(ns: String, name: String, value: Boolean) extends LibraryServiceMessage
  case object IndexAll extends LibraryServiceMessage
  case class FindDocuments(criteria: LibrarySearchParams) extends LibraryServiceMessage
  case class Suggest(criteria: LibrarySearchParams) extends LibraryServiceMessage
  case class PopulateSuggest(field: String, text: String) extends LibraryServiceMessage

  def props(libraryServiceConstructor: UserInfo => LibraryService, userInfo: UserInfo): Props = {
    Props(libraryServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new LibraryService(userInfo, app.rawlsDAO, app.searchDAO)
}


class LibraryService (protected val argUserInfo: UserInfo, val rawlsDAO: RawlsDAO, val searchDAO: SearchDAO)(implicit protected val executionContext: ExecutionContext) extends Actor
  with LibraryServiceSupport with AttributeSupport with RoleSupport with SprayJsonSupport with LazyLogging {

  lazy val log = LoggerFactory.getLogger(getClass)

  implicit val userInfo = argUserInfo

  override def receive = {
    case UpdateAttributes(ns: String, name: String, attrsJsonString: String) => updateAttributes(ns, name, attrsJsonString) pipeTo sender
    case UpdateDiscoverableByGroups(ns: String, name: String, newGroups: Seq[String]) => updateDiscoverableByGroups(ns, name, newGroups) pipeTo sender
    case SetPublishAttribute(ns: String, name: String, value: Boolean) => setWorkspaceIsPublished(ns, name, value) pipeTo sender
    case IndexAll => asAdmin {indexAll} pipeTo sender
    case FindDocuments(criteria: LibrarySearchParams) => findDocuments(criteria) pipeTo sender
    case Suggest(criteria: LibrarySearchParams) => suggest(criteria) pipeTo sender
    case PopulateSuggest(field: String, text: String) => populateSuggest(field: String, text: String) pipeTo sender
  }

  def hasAccessOrCurator(workspaceResponse: WorkspaceResponse, neededLevel: WorkspaceAccessLevels.WorkspaceAccessLevel): Future[Boolean] = {
    val wsaccess = workspaceResponse.accessLevel >= neededLevel
    if (!wsaccess)
      tryIsCurator(userInfo)
    else
      Future.successful(wsaccess)
  }

  def isPublished(workspaceResponse: WorkspaceResponse): Boolean = {
    workspaceResponse.workspace.attributes.get(publishedFlag).fold(false)(_.asInstanceOf[AttributeBoolean].value)
  }

  def updateDiscoverableByGroups(ns: String, name: String, newGroups: Seq[String]): Future[PerRequestMessage] = {
    if (newGroups.forall { g => FireCloudConfig.ElasticSearch.discoverGroupNames.contains(g) }) {
      rawlsDAO.getWorkspace(ns, name) map { workspaceResponse =>
        if (workspaceResponse.accessLevel >= WorkspaceAccessLevels.Owner) {
          // this is technically vulnerable to a race condition in which the workspace attributes have changed
          // between the time we retrieved them and here, where we update them.
          val remove = Seq(RemoveAttribute(discoverableWSAttribute))
          val operations = newGroups map (group => AddListMember(discoverableWSAttribute, new AttributeString(group)))
          RequestComplete(patchWorkspace(ns, name, remove ++ operations, isPublished(workspaceResponse)))
        } else {
          RequestCompleteWithErrorReport(Forbidden, "must be an owner to set discoverable groups")
        }
      }
    } else {
      Future(RequestCompleteWithErrorReport(BadRequest, s"groups must be subset of allowable groups: %s".format(FireCloudConfig.ElasticSearch.discoverGroupNames.toArray.mkString(", "))))
    }
  }

  /*
   * Library metadata attributes can only be modified by someone with write or higher permissions
   * for the workspace
   */
  def updateAttributes(ns: String, name: String, attrsJsonString: String): Future[PerRequestMessage] = {
    // attributes come in as standard json so we can use json schema for validation. Thus,
    // we need to use the plain-array deserialization.
    implicit val impAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer
    // we accept a string here, not a JsValue so we can most granularly handle json parsing

    Try(attrsJsonString.parseJson.asJsObject.convertTo[AttributeMap]) match {
      case Failure(ex:ParsingException) => Future(RequestCompleteWithErrorReport(BadRequest, "Invalid json supplied", ex))
      case Failure(e) => Future(RequestCompleteWithErrorReport(BadRequest, BadRequest.defaultMessage, e))
      case Success(userAttrs) =>
        val validationResult = Try( schemaValidate(attrsJsonString) )
        validationResult match {
          case Failure(ve: ValidationException) =>
            val errorMessages = getSchemaValidationMessages(ve)
            val errorReports = errorMessages map {ErrorReport(_)}
            Future(RequestCompleteWithErrorReport(BadRequest, errorMessages.mkString("; "), errorReports))
          case Failure(e) =>
            Future(RequestCompleteWithErrorReport(BadRequest, BadRequest.defaultMessage, e))
          case Success(x) => {
            rawlsDAO.getWorkspace(ns, name) map  { workspaceResponse =>
              // this is technically vulnerable to a race condition in which the workspace attributes have changed
              // between the time we retrieved them and here, where we update them.
              val allOperations = generateAttributeOperations(workspaceResponse.workspace.attributes, userAttrs,
                k => k.namespace == AttributeName.libraryNamespace && k.name != LibraryService.publishedFlag.name)
              RequestComplete(patchWorkspace(ns, name, allOperations, isPublished(workspaceResponse)))
            }
          }
        }
    }
  }

  /*
   * Update workspace in rawls. If the dataset is currently published, republish with changes
   */
  def patchWorkspace(ns: String, name: String, allOperations: Seq[AttributeUpdateOperation], isPublished: Boolean) : Future[Workspace] = {
    rawlsDAO.patchWorkspaceAttributes(ns, name, allOperations) map { newws =>
      if (isPublished) {
        // if already published, republish
        // we do not need to delete before republish
        publishDocument(newws)
      }
      newws
    }
  }

  def setWorkspaceIsPublished(ns: String, name: String, value: Boolean): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
      val pub = isPublished(workspaceResponse)
      val requiredLevel = if (!pub || (pub && !value)) WorkspaceAccessLevels.Owner else WorkspaceAccessLevels.Write
      if (workspaceResponse.accessLevel >= requiredLevel) {
        val operations = updatePublishAttribute(value)
        rawlsDAO.patchWorkspaceAttributes(ns, name, operations) map { ws =>
          if (value)
            publishDocument(ws)
          else
            removeDocument(ws)
          RequestComplete(ws)
        }
      } else {
        Future(RequestCompleteWithErrorReport(Forbidden, s"must have %s privileges".format(requiredLevel.toString())))
      }
    }
  }

  def publishDocument(ws: Workspace): Unit = {
    searchDAO.indexDocument(indexableDocument(ws))
  }

  def removeDocument(ws: Workspace): Unit = {
    searchDAO.deleteDocument(ws.workspaceId)
  }

  def indexAll: Future[PerRequestMessage] = {
    rawlsDAO.getAllLibraryPublishedWorkspaces map {workspaces =>
      searchDAO.recreateIndex()
      if (workspaces.isEmpty)
        RequestComplete(NoContent)
      else {
        val toIndex:Seq[Document] = workspaces.map {workspace => indexableDocument(workspace)}
        val bi = searchDAO.bulkIndex(toIndex)
        val statusCode = if (bi.hasFailures) {
          InternalServerError
        } else {
          OK
        }
        RequestComplete(statusCode, bi)
      }
    }
  }

  def findDocuments(criteria: LibrarySearchParams): Future[PerRequestMessage] = {
    getEffectiveDiscoverGroups(rawlsDAO) map { userGroups =>
      val docsFuture = searchDAO.findDocuments(criteria, userGroups)
      val idsFuture = rawlsDAO.getWorkspaces
      val searchResults = for {
        docs <- docsFuture
        ids <- idsFuture
      } yield updateAccess(docs, ids)
      (RequestComplete(searchResults))
    }
  }

  def suggest(criteria: LibrarySearchParams): Future[PerRequestMessage] = {
    getEffectiveDiscoverGroups(rawlsDAO) map {userGroups =>
      searchDAO.suggestionsFromAll(criteria, userGroups)} map (RequestComplete(_))
  }

  def populateSuggest(field: String, text: String): Future[PerRequestMessage] = {
    searchDAO.suggestionsForFieldPopulate(field, text) map {(RequestComplete(_))} recoverWith {
      case e: FireCloudException => Future(RequestCompleteWithErrorReport(BadRequest, s"suggestions not available for field %s".format(field)))
    }
  }
}
