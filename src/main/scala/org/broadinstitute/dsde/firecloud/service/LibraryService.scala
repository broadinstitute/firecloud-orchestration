package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, SearchDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impRawlsWorkspace
import org.broadinstitute.dsde.firecloud.model.{Document, ErrorReport, RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.LibraryService._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.RoleSupport
import org.everit.json.schema.ValidationException
import org.parboiled.common.FileUtils
import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport
import spray.json.JsonParser.ParsingException
import spray.json._

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


/**
  * Created by davidan on 9/23/16.
  */

object LibraryService {
  final val publishedFlag = "library:published"
  final val schemaLocation = "library/attribute-definitions.json"

  sealed trait LibraryServiceMessage
  case class UpdateAttributes(ns: String, name: String, attrsJsonString: String) extends LibraryServiceMessage
  case class SetPublishAttribute(ns: String, name: String, value: Boolean) extends LibraryServiceMessage
  case object IndexAll extends LibraryServiceMessage
  case object TestMappings extends LibraryServiceMessage

  def props(libraryServiceConstructor: UserInfo => LibraryService, userInfo: UserInfo): Props = {
    Props(libraryServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new LibraryService(userInfo, app.rawlsDAO, app.searchDAO)
}


class LibraryService (protected val argUserInfo: UserInfo, val rawlsDAO: RawlsDAO, val searchDAO: SearchDAO)(implicit protected val executionContext: ExecutionContext) extends Actor
  with LibraryServiceSupport with RoleSupport with SprayJsonSupport with LazyLogging {

  lazy val log = LoggerFactory.getLogger(getClass)

  implicit val userInfo = argUserInfo

  override def receive = {
    case UpdateAttributes(ns: String, name: String, attrsJsonString: String) => asCurator {updateAttributes(ns, name, attrsJsonString)} pipeTo sender
    case SetPublishAttribute(ns: String, name: String, value: Boolean) => asCurator {setWorkspaceIsPublished(ns, name, value)} pipeTo sender
    case IndexAll => asAdmin {indexAll} pipeTo sender
    case TestMappings => printMappings pipeTo sender
  }

  def updateAttributes(ns: String, name: String, attrsJsonString: String): Future[PerRequestMessage] = {
    // we accept a string here, not a JsValue so we can most granularly handle json parsing
    Try(attrsJsonString.parseJson.asJsObject) match {
      case Failure(ex:ParsingException) => Future(RequestCompleteWithErrorReport(BadRequest, "Invalid json supplied", ex))
      case Failure(e) => Future(RequestCompleteWithErrorReport(BadRequest, BadRequest.defaultMessage, e))
      case Success(userAttrs) =>
        val validationResult = Try( schemaValidate(userAttrs) )
        validationResult match {
          case Failure(ve: ValidationException) =>
            val errorReports = ve.getCausingExceptions.map{v => ErrorReport(v.getMessage)}
            val userMsg = if (errorReports.size > 1)
              ve.getMessage + ". See causes for details."
            else
              ve.getMessage
            Future(RequestCompleteWithErrorReport(BadRequest, userMsg, errorReports))
          case Failure(e) =>
            Future(RequestCompleteWithErrorReport(BadRequest, BadRequest.defaultMessage, e))
          case Success(x) => {
            rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
              // this is technically vulnerable to a race condition in which the workspace attributes have changed
              // between the time we retrieved them and here, where we update them.
              val allOperations = generateAttributeOperations(workspaceResponse.workspace.get.attributes, userAttrs)
              rawlsDAO.patchWorkspaceAttributes(ns, name, allOperations) map (RequestComplete(_))
            }
          }
        }
    }
  }

  def setWorkspaceIsPublished(ns: String, name: String, value: Boolean): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
      // verify owner on workspace
      if (!workspaceResponse.accessLevel.contains("OWNER")) {
        Future(RequestCompleteWithErrorReport(Forbidden, "must be an owner"))
      } else {
        val operations = updatePublishAttribute(value)
        rawlsDAO.patchWorkspaceAttributes(ns, name, operations) map (RequestComplete(_))
      }
    }
  }

  def indexAll: Future[PerRequestMessage] = {
    rawlsDAO.getAllLibraryPublishedWorkspaces map {workspaces =>
      val toIndex:Seq[Document] = workspaces.map {workspace => indexableDocument(workspace)}
      searchDAO.recreateIndex
      val indexResult = searchDAO.bulkIndex(toIndex)
      RequestComplete(OK, indexResult.toString)
    }
  }

  def printMappings: Future[PerRequestMessage] = {
    val jsonContents = FileUtils.readAllTextFromResource(schemaLocation)
      Future.successful(RequestComplete(OK, searchDAO.makeESMapping(jsonContents)))
  }

}
