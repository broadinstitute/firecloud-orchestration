package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudException}
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, SearchDAO}
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.LibraryService._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.RoleSupport
import org.everit.json.schema.ValidationException
import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport
import spray.json.JsonParser.ParsingException
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object LibraryService {
  final val publishedFlag = AttributeName("library","published")
  final val schemaLocation = "library/attribute-definitions.json"

  sealed trait LibraryServiceMessage
  case class UpdateAttributes(ns: String, name: String, attrsJsonString: String) extends LibraryServiceMessage
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
    case UpdateAttributes(ns: String, name: String, attrsJsonString: String) => asCurator {updateAttributes(ns, name, attrsJsonString)} pipeTo sender
    case SetPublishAttribute(ns: String, name: String, value: Boolean) => asCurator {setWorkspaceIsPublished(ns, name, value)} pipeTo sender
    case IndexAll => asAdmin {indexAll} pipeTo sender
    case FindDocuments(criteria: LibrarySearchParams) => asCurator {findDocuments(criteria)} pipeTo sender
    case Suggest(criteria: LibrarySearchParams) => asCurator {suggest(criteria)} pipeTo sender
    case PopulateSuggest(field: String, text: String) => asCurator {populateSuggest(field: String, text: String)} pipeTo sender
  }

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
            rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
              // this is technically vulnerable to a race condition in which the workspace attributes have changed
              // between the time we retrieved them and here, where we update them.
              val allOperations = generateAttributeOperations(workspaceResponse.workspace.attributes, userAttrs,
                k => k.namespace == AttributeName.libraryNamespace && k.name != LibraryService.publishedFlag.name)
              rawlsDAO.patchWorkspaceAttributes(ns, name, allOperations) map (RequestComplete(_))
            }
          }
        }
    }
  }

  def setWorkspaceIsPublished(ns: String, name: String, value: Boolean): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
      // verify owner on workspace
      if (!workspaceResponse.accessLevel.contains("OWNER") && !workspaceResponse.accessLevel.contains("PROJECT_OWNER")) {
        Future(RequestCompleteWithErrorReport(Forbidden, "must be an owner"))
      } else {
        val operations = updatePublishAttribute(value)
        rawlsDAO.patchWorkspaceAttributes(ns, name, operations) map { ws =>
          if (value)
            searchDAO.indexDocument(indexableDocument(ws))
          else
            searchDAO.deleteDocument(ws.workspaceId)
          RequestComplete(ws)
        }
      }
    }
  }

  def indexAll: Future[PerRequestMessage] = {
    rawlsDAO.getAllLibraryPublishedWorkspaces map {workspaces =>
      if (workspaces.isEmpty)
        RequestComplete(NoContent)
      else {
        val toIndex:Seq[Document] = workspaces.map {workspace => indexableDocument(workspace)}
        searchDAO.recreateIndex
        val indexResult = searchDAO.bulkIndex(toIndex)
        RequestComplete(OK, indexResult.toString)
      }
    }
  }

  def findDocuments(criteria: LibrarySearchParams): Future[PerRequestMessage] = {
    rawlsDAO.getGroupsForUser map (searchDAO.findDocuments(criteria, _)) map (RequestComplete(_))
  }

  def suggest(criteria: LibrarySearchParams): Future[PerRequestMessage] = {
    rawlsDAO.getGroupsForUser map (searchDAO.suggestionsFromAll(criteria, _)) map (RequestComplete(_))
  }

  def populateSuggest(field: String, text: String): Future[PerRequestMessage] = {
    searchDAO.suggestionsForFieldPopulate(field, text) map {(RequestComplete(_))} recoverWith {
      case e: FireCloudException => Future(RequestCompleteWithErrorReport(BadRequest, s"suggestions not available for field %s".format(field)))
    }
  }
}
