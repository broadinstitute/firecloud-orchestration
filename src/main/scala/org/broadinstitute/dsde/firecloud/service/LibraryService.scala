package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.firecloud.model._
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
  final val publishedFlag = AttributeName.withLibraryNS("published")
  final val discoverableWSAttribute = AttributeName.withLibraryNS("discoverableByGroups")
  final val orspIdAttribute = AttributeName.withLibraryNS("orsp")
  final val schemaLocation = "library/attribute-definitions.json"

  sealed trait LibraryServiceMessage
  case class UpdateLibraryMetadata(ns: String, name: String, attrsJsonString: String, validate: Boolean) extends LibraryServiceMessage
  case class GetLibraryMetadata(ns: String, name: String) extends LibraryServiceMessage
  case class UpdateDiscoverableByGroups(ns: String, name: String, newGroups: Seq[String]) extends LibraryServiceMessage
  case class GetDiscoverableByGroups(ns: String, name: String) extends LibraryServiceMessage
  case class SetPublishAttribute(ns: String, name: String, value: Boolean) extends LibraryServiceMessage
  case object IndexAll extends LibraryServiceMessage
  case class FindDocuments(criteria: LibrarySearchParams) extends LibraryServiceMessage
  case class Suggest(criteria: LibrarySearchParams) extends LibraryServiceMessage
  case class PopulateSuggest(field: String, text: String) extends LibraryServiceMessage

  def props(libraryServiceConstructor: UserInfo => LibraryService, userInfo: UserInfo): Props = {
    Props(libraryServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new LibraryService(userInfo, app.rawlsDAO, app.samDAO, app.searchDAO, app.ontologyDAO, app.consentDAO)
}


class LibraryService (protected val argUserInfo: UserInfo,
                      val rawlsDAO: RawlsDAO,
                      val samDao: SamDAO,
                      val searchDAO: SearchDAO,
                      val ontologyDAO: OntologyDAO,
                      val consentDAO: ConsentDAO)
                     (implicit protected val executionContext: ExecutionContext) extends Actor
  with LibraryServiceSupport with AttributeSupport with PermissionsSupport with SprayJsonSupport with LazyLogging with WorkspacePublishingSupport {

  lazy val log = LoggerFactory.getLogger(getClass)

  implicit val userToken = argUserInfo

  // attributes come in as standard json so we can use json schema for validation. Thus,
  // we need to use the plain-array deserialization.
  implicit val impAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer

  override def receive = {
    case UpdateLibraryMetadata(ns: String, name: String, attrsJsonString: String, validate: Boolean) => updateLibraryMetadata(ns, name, attrsJsonString, validate) pipeTo sender
    case GetLibraryMetadata(ns: String, name: String) => getLibraryMetadata(ns, name) pipeTo sender
    case UpdateDiscoverableByGroups(ns: String, name: String, newGroups: Seq[String]) => updateDiscoverableByGroups(ns, name, newGroups) pipeTo sender
    case GetDiscoverableByGroups(ns: String, name: String) => getDiscoverableByGroups(ns, name) pipeTo sender
    case SetPublishAttribute(ns: String, name: String, value: Boolean) => setWorkspaceIsPublished(ns, name, value) pipeTo sender
    case IndexAll => asAdmin {indexAll} pipeTo sender
    case FindDocuments(criteria: LibrarySearchParams) => findDocuments(criteria) pipeTo sender
    case Suggest(criteria: LibrarySearchParams) => suggest(criteria) pipeTo sender
    case PopulateSuggest(field: String, text: String) => populateSuggest(field: String, text: String) pipeTo sender
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

  def getDiscoverableByGroups(ns: String, name: String): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(ns, name) map { workspaceResponse =>
      val groups = workspaceResponse.workspace.attributes.get(discoverableWSAttribute) match {
        case Some(vals:AttributeValueList) => vals.list.collect{
          case s:AttributeString => s.value
        }
        case _ => List.empty[String]
      }
      RequestComplete(OK, groups.sortBy(_.toLowerCase))
    }
  }

  private def isInvalid(attrsJsonString: String): (Boolean, Option[String]) = {
    val validationResult = Try(schemaValidate(attrsJsonString))
    validationResult match {
      case Failure(ve: ValidationException) => (true, Some(getSchemaValidationMessages(ve).mkString("; ")))
      case Failure(e) => (true, Some(e.getMessage))
      case Success(x) => (false, None)
    }
  }

  def updateLibraryMetadata(ns: String, name: String, attrsJsonString: String, validate: Boolean): Future[PerRequestMessage] = {
    // we accept a string here, not a JsValue so we can most granularly handle json parsing

    Try(attrsJsonString.parseJson.asJsObject.convertTo[AttributeMap]) match {
      case Failure(ex:ParsingException) => Future(RequestCompleteWithErrorReport(BadRequest, "Invalid json supplied", ex))
      case Failure(e) => Future(RequestCompleteWithErrorReport(BadRequest, BadRequest.defaultMessage, e))
      case Success(userAttrs) =>
        val (invalid, errorMessage): (Boolean, Option[String]) = isInvalid(attrsJsonString)
        rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
          val published = isPublished(workspaceResponse)
          if (invalid && (published || validate)) {
            Future(RequestCompleteWithErrorReport(BadRequest, errorMessage.getOrElse(BadRequest.defaultMessage)))
          } else {
            // because not all editors can update discoverableByGroups, if the request does not include discoverableByGroups
            // or if it is not being changed, don't include it in the update operations (less restrictive permissions will
            // be checked by rawls)
            val modDiscoverability = userAttrs.contains(discoverableWSAttribute) && isDiscoverableDifferent(workspaceResponse, userAttrs)
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
            internalPatchWorkspaceAndRepublish(ns, name, allOperations, published) map (RequestComplete(_))
          }
        }
    }
  }

  def getLibraryMetadata(ns: String, name: String): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
      val allAttrs = workspaceResponse.workspace.attributes
      val libAttrs = allAttrs.filter {
        case ((LibraryService.publishedFlag,v)) => false
        case ((k,v)) if k.namespace == AttributeName.libraryNamespace => true
        case _ => false
      }
      Future(RequestComplete(OK, libAttrs))
    }
  }

  /*
   * Will republish if it is currently in the published state.
   */
  private def internalPatchWorkspaceAndRepublish(ns: String, name: String, allOperations: Seq[AttributeUpdateOperation], isPublished: Boolean): Future[Workspace] = {
      rawlsDAO.updateLibraryAttributes(ns, name, allOperations) map { newws =>
      republishDocument(newws, ontologyDAO, searchDAO, consentDAO)
      newws
    }
  }

  // should only be used to change published state
  def setWorkspaceIsPublished(ns: String, name: String, publishArg: Boolean): Future[PerRequestMessage] = {
    rawlsDAO.getWorkspace(ns, name) flatMap { workspaceResponse =>
      val currentPublished = isPublished(workspaceResponse)
      // only need to validate metadata if we are actually publishing
      val (invalid, errorMessage) = if (publishArg && !currentPublished)
        isInvalid(workspaceResponse.workspace.attributes.toJson.compactPrint)
      else
        (false, None)

      if (currentPublished == publishArg)
        // user request would result in no change; just return as noop.
        Future(RequestComplete(NoContent))
      else if (invalid)
        // user requested a publish, but metadata is invalid; return error.
        Future(RequestCompleteWithErrorReport(BadRequest, errorMessage.getOrElse(BadRequest.defaultMessage)))
      else {
        // user requested a change in published flag, and metadata is valid; make the change.
        setWorkspacePublishedStatus(workspaceResponse.workspace, publishArg, rawlsDAO, ontologyDAO, searchDAO, consentDAO) map { ws =>
          RequestComplete(ws)
        }
      }
    }
  }

  def indexAll: Future[PerRequestMessage] = {
    logger.info("reindex: requesting workspaces from rawls ...")
    rawlsDAO.getAllLibraryPublishedWorkspaces flatMap { workspaces: Seq[Workspace] =>
      if (workspaces.isEmpty)
        Future(RequestComplete(NoContent))
      else {
        logger.info("reindex: requesting ontology parents for workspaces ...")
        val toIndex: Future[Seq[Document]] = indexableDocuments(workspaces, ontologyDAO, consentDAO)
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
    getEffectiveDiscoverGroups(rawlsDAO) flatMap { userGroups =>
      // we want docsFuture and ids to be parallelized - so declare them here, outside
      // of the for-yield.
      val docsFuture = searchDAO.findDocuments(criteria, userGroups)
      val idsFuture = rawlsDAO.getWorkspaces

      (for {
        docs <- docsFuture
        ids <- idsFuture
      } yield RequestComplete(updateAccess(docs, ids))) recover {
        case ex =>
          throw new FireCloudExceptionWithErrorReport(ErrorReport(errorMessageFromSearchException(ex)))
      }
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

  private def errorMessageFromSearchException(ex: Throwable): String = {
    // elasticsearch errors are often nested, try to dig into them safely to find a message
    val message = if (ex.getCause != null) {
      val firstCause = ex.getCause
      if (firstCause.getCause != null) {
        firstCause.getCause.getMessage
      } else {
        firstCause.getMessage
      }
    } else {
      ex.getMessage
    }

    Option(message) match {
      case Some(m:String) => m
      case _ => "Unknown error during search."
    }

  }

}
