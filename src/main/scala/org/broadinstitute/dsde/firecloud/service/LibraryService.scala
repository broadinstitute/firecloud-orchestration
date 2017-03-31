package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException}
import org.broadinstitute.dsde.firecloud.dataaccess.{OntologyDAO, RawlsDAO, SearchDAO}
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, _}
import org.broadinstitute.dsde.firecloud.service.LibraryService._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.PermissionsSupport
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
    new LibraryService(userInfo, app.rawlsDAO, app.searchDAO, app.ontologyDAO)
}


class LibraryService (protected val argUserInfo: UserInfo,
                      val rawlsDAO: RawlsDAO,
                      val searchDAO: SearchDAO,
                      val ontologyDAO: OntologyDAO)
                     (implicit protected val executionContext: ExecutionContext) extends Actor
  with LibraryServiceSupport with AttributeSupport with PermissionsSupport with SprayJsonSupport with LazyLogging {

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

  def isPublished(workspaceResponse: WorkspaceResponse): Boolean = {
    workspaceResponse.workspace.attributes.get(publishedFlag).fold(false)(_.asInstanceOf[AttributeBoolean].value)
  }

  def updateDiscoverableByGroups(ns: String, name: String, newGroups: Seq[String]): Future[PerRequestMessage] = {
    if (newGroups.forall { g => FireCloudConfig.ElasticSearch.discoverGroupNames.contains(g) }) {
      rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
        // this is technically vulnerable to a race condition in which the workspace attributes have changed
        // between the time we retrieved them and here, where we update them.
        val remove = Seq(RemoveAttribute(discoverableWSAttribute))
        val operations = newGroups map (group => AddListMember(discoverableWSAttribute, AttributeString(group)))
          internalPatchWorkspaceAndRepublish(ns, name, remove ++ operations, isPublished(workspaceResponse)) map (RequestComplete(_))
      }
    } else {
      Future(RequestCompleteWithErrorReport(BadRequest, s"groups must be subset of allowable groups: %s".format(FireCloudConfig.ElasticSearch.discoverGroupNames.toArray.mkString(", "))))
    }
  }

  /*
   * Library metadata attributes can only be modified by someone with write or higher permissions,
   * or with a combination of read and catalog permissions
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
          case Success(x) =>
            rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
              val modDiscoverability = isDiscoverableDifferent(workspaceResponse, userAttrs)
              val skipAttributes =
                if (modDiscoverability)
                  Seq(publishedFlag)
                else
                  // if discoverable by groups is not being changed, then skip it (i.e. don't delete from ws)
                  Seq(publishedFlag, discoverableWSAttribute)

              // this is technically vulnerable to a race condition in which the workspace attributes have changed
              // between the time we retrieved them and here, where we update them.
              val allOperations = generateAttributeOperations(workspaceResponse.workspace.attributes, userAttrs,
                k => k.namespace == AttributeName.libraryNamespace && !skipAttributes.contains(k))
              internalPatchWorkspaceAndRepublish(ns, name, allOperations, isPublished(workspaceResponse)) map (RequestComplete(_))
            }
        }
    }
  }

  def isDiscoverableDifferent(workspaceResponse: WorkspaceResponse, userAttrs: AttributeMap): Boolean = {
    def convert(list: Option[Attribute]): Seq[String] = {
      list match {
        case Some(x) if x.isInstanceOf[AttributeValueList] => x.asInstanceOf[AttributeValueList].list.asInstanceOf[Seq[AttributeString]] map { str => str.value }
        case _ => Seq.empty
      }
    }
    val current = convert(workspaceResponse.workspace.attributes.get(discoverableWSAttribute))
    val newvals = convert(userAttrs.get(discoverableWSAttribute))

    if (current.isEmpty && newvals.isEmpty)
      false
    else if (current.nonEmpty && newvals.nonEmpty) {
      current.toSet.diff(newvals.toSet).nonEmpty
    } else
    true
  }

  /*
   * Uses admin credentials if necessary to update the workspace in rawls. Will republish if it is currently in the published state.
   * Code that uses this should ensure the user has the required properties (especially is they do not have write+)
   */
  def internalPatchWorkspaceAndRepublish(ns: String, name: String, allOperations: Seq[AttributeUpdateOperation], isPublished: Boolean): Future[Workspace] = {
      rawlsDAO.updateLibraryAttributes(ns, name, allOperations) map { newws =>
      if (isPublished) {
        // if already published, republish
        // we do not need to delete before republish
        publishDocument(newws)
      }
      newws
    }
  }


  // should only be used to change published state
  def setWorkspaceIsPublished(ns: String, name: String, value: Boolean): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
      val pub = isPublished(workspaceResponse)
      if (pub == value)
        Future(RequestComplete(NoContent))
      else {
        rawlsDAO.updateLibraryAttributes(ns, name, updatePublishAttribute(value)) map { ws =>
          if (value)
            publishDocument(ws)
          else
            removeDocument(ws)
          RequestComplete(ws)
        }
      }
    }
  }

  def publishDocument(ws: Workspace): Unit = {
    indexableDocuments(Seq(ws), ontologyDAO) map { ws =>
      assert(ws.size == 1)
      searchDAO.indexDocument(ws.head)
    }
  }

  def removeDocument(ws: Workspace): Unit = {
    searchDAO.deleteDocument(ws.workspaceId)
  }

  def indexAll: Future[PerRequestMessage] = {
    logger.info("reindex: requesting workspaces from rawls ...")
    rawlsDAO.getAllLibraryPublishedWorkspaces flatMap { workspaces: Seq[Workspace] =>
      if (workspaces.isEmpty)
        Future(RequestComplete(NoContent))
      else {
        logger.info("reindex: requesting ontology parents for workspaces ...")
        val toIndex: Future[Seq[Document]] = indexableDocuments(workspaces, ontologyDAO)
        toIndex map { documents =>
          logger.info("reindex: resetting index ...")
          searchDAO.recreateIndex()
          logger.info("reindex: indexing datasets ...")
          val indexedDocuments = searchDAO.bulkIndex(documents)
          logger.info("reindex: ... done.")
          if (indexedDocuments.hasFailures) {
            RequestComplete(InternalServerError, indexedDocuments)
          } else {
            RequestComplete(OK, indexedDocuments)
          }
        }
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
      RequestComplete(searchResults)
    }
  }

  def suggest(criteria: LibrarySearchParams): Future[PerRequestMessage] = {
    getEffectiveDiscoverGroups(rawlsDAO) map {userGroups =>
      searchDAO.suggestionsFromAll(criteria, userGroups)} map (RequestComplete(_))
  }

  def populateSuggest(field: String, text: String): Future[PerRequestMessage] = {
    searchDAO.suggestionsForFieldPopulate(field, text) map {RequestComplete(_)} recoverWith {
      case e: FireCloudException => Future(RequestCompleteWithErrorReport(BadRequest, s"suggestions not available for field %s".format(field)))
    }
  }
}
