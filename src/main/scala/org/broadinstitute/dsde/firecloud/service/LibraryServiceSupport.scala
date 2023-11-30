package org.broadinstitute.dsde.firecloud.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{ConsentDAO, OntologyDAO, RawlsDAO, SamDAO}
import org.broadinstitute.dsde.firecloud.model.DUOS.DuosDataUse
import org.broadinstitute.dsde.firecloud.model.DataUse._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Ontology.TermParent
import org.broadinstitute.dsde.firecloud.model.{ConsentCodes, Document, ElasticSearch, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.LibraryService.orspIdAttribute
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.rawls.model._
import org.everit.json.schema.loader.SchemaLoader
import org.everit.json.schema.{Schema, ValidationException}
import org.json.{JSONObject, JSONTokener}
import org.parboiled.common.FileUtils
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
  * Created by davidan on 10/2/16.
  */
trait LibraryServiceSupport extends DataUseRestrictionSupport with LazyLogging {

  implicit val userToken: WithAccessToken

  def updatePublishAttribute(value: Boolean): Seq[AttributeUpdateOperation] = {
    if (value) Seq(AddUpdateAttribute(LibraryService.publishedFlag, AttributeBoolean(true)))
    else Seq(RemoveAttribute(LibraryService.publishedFlag))
  }

  def indexableDocuments(workspaces: Seq[WorkspaceDetails], ontologyDAO: OntologyDAO, consentDAO: ConsentDAO)(implicit userToken: WithAccessToken, ec: ExecutionContext): Future[Seq[Document]] = {
    // find all the ontology nodes in this list of workspaces
    val nodes = uniqueWorkspaceStringAttributes(workspaces, AttributeName.withLibraryNS("diseaseOntologyID"))

    // query ontology for this set of nodes, save in a map
    val parentCache = nodes map { id => (id, lookupParentNodes(id, ontologyDAO)) }
    val parentMap = parentCache.toMap.filter(e => e._2.nonEmpty) // remove nodes that have no parent
    logger.debug(s"have parent results for ${parentMap.size} ontology nodes")

    // as of Nov 27 2023 (actually "some time ago" before that), the DUOS/Consent system no longer supports
    // lookup of ORSP ids; these lookups will always return 404. The code below previously looked up DU attributes
    // from consent for a given ORSP id and updated the workspace attributes with consent's result. Now, the code
    // simply updates workspace attributes with a Map.empty, reflecting the fact that consent always 404s.
    val annotatedWorkspaces = workspaces map { ws =>
      // does this workspace have an orsp id?
      ws.attributes.getOrElse(Map.empty).get(orspIdAttribute) match {
        case Some(_) =>
          // if so, remove explicit DU attributes
          val newAttrs = replaceDataUseAttributes(ws.attributes.getOrElse(Map.empty), Map.empty[AttributeName, Attribute])
          ws.copy(attributes = Option(newAttrs))
        case _ =>
          // this workspace does not have an ORSP id; leave it untouched
          ws
      }
    }
    Future.successful(annotatedWorkspaces map { w => indexableDocument(w, parentMap, ontologyDAO) })
  }

  private def indexableDocument(workspace: WorkspaceDetails, parentCache: Map[String,Seq[TermParent]], ontologyDAO: OntologyDAO)(implicit ec: ExecutionContext): Document = {
    val attrfields_subset = workspace.attributes.getOrElse(Map.empty).filter(_._1.namespace == AttributeName.libraryNamespace)
    val attrfields = attrfields_subset map { case (attr, value) =>
      attr.name match {
        case "discoverableByGroups" => AttributeName.withDefaultNS(ElasticSearch.fieldDiscoverableByGroups) -> value
        case _ => attr -> value
      }
    }
    val idfields = Map(
      AttributeName.withDefaultNS("name") -> AttributeString(workspace.name),
      AttributeName.withDefaultNS("namespace") -> AttributeString(workspace.namespace),
      AttributeName.withDefaultNS("workspaceId") -> AttributeString(workspace.workspaceId),
      AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(workspace.authorizationDomain.getOrElse(Set.empty).map(group => AttributeString(group.membersGroupName.value)).toSeq)
    )

    val tagfields = workspace.attributes.getOrElse(Map.empty).get(AttributeName.withTagsNS()) match {
      case Some(t) => Map(AttributeName.withTagsNS() -> t)
      case None => Map()
    }

    val durAttributeNames = ConsentCodes.allPreviousDurFieldNames.map(AttributeName.withLibraryNS)
    val structuredAndDisplayAttributes = generateStructuredAndDisplayAttributes(workspace, ontologyDAO)
    val (dur, displayDur) = (structuredAndDisplayAttributes.structured, structuredAndDisplayAttributes.display)

    val fields = (attrfields -- durAttributeNames) ++ idfields ++ tagfields ++ dur ++ displayDur

    workspace.attributes.getOrElse(Map.empty).get(AttributeName.withLibraryNS("diseaseOntologyID")) match {
      case Some(id: AttributeString) =>
        val parents = parentCache.get(id.value)
        val parentFields = if (parents.isDefined) {
          fields + (AttributeName.withDefaultNS("parents") -> AttributeValueRawJson(parents.get.map(_.toESTermParent).toJson.compactPrint))
        } else {
          fields
        }
        Document(workspace.workspaceId, parentFields)
      case _ => Document(workspace.workspaceId, fields)
    }
  }

  def uniqueWorkspaceStringAttributes(workspaces: Seq[WorkspaceDetails], attributeName: AttributeName): Set[String] = {
    val valueSeq:Seq[String] = workspaces.collect {
      case w if w.attributes.getOrElse(Map.empty).contains(attributeName) =>
        w.attributes.getOrElse(Map.empty)(attributeName)
    }.collect {
      case s:AttributeString => s.value
    }
    logger.debug(s"found ${valueSeq.size} workspaces with ${AttributeName.toDelimitedName(attributeName)} string attributes")

    val valueSet = valueSeq.toSet
    logger.debug(s"found ${valueSet.size} unique ${AttributeName.toDelimitedName(attributeName)} values")

    valueSet
  }

  // wraps the ontologyDAO call, handles Nones/nulls, and returns a [Future[Seq].
  // the Seq is populated if the leaf node exists and has parents; Seq is empty otherwise.
  def lookupParentNodes(leafId:String, ontologyDAO: OntologyDAO)(implicit ec: ExecutionContext):Seq[TermParent] = {
    Try(ontologyDAO.search(leafId)) match {
      case Success(terms) if terms.nonEmpty =>
        terms.head.parents.getOrElse(Seq.empty)
      case Success(empty) => Seq.empty[TermParent]
      case Failure(ex) =>
        logger.warn(s"exception getting term and parents from ontology: ${ex.getMessage}")
        Seq.empty[TermParent]
    }
  }

  def defaultSchema: String = FileUtils.readAllTextFromResource(LibraryService.schemaLocation)

  def schemaValidate(data: String): Unit = validateJsonSchema(data, defaultSchema)
  def schemaValidate(data: JsObject): Unit = validateJsonSchema(data.compactPrint, defaultSchema)

  def validateJsonSchema(data: String, schemaStr: String): Unit = {
    val rawSchema:JSONObject = new JSONObject(new JSONTokener(schemaStr))
    val schema:Schema = SchemaLoader.load(rawSchema)
    schema.validate(new JSONObject(data))
  }

  def getSchemaValidationMessages(ve: ValidationException): Seq[String] = {
    Seq(ve.getPointerToViolation + ": " + ve.getErrorMessage) ++
      (ve.getCausingExceptions.asScala flatMap getSchemaValidationMessages)
  }

  def getEffectiveDiscoverGroups(samDAO: SamDAO)(implicit ec: ExecutionContext, userInfo:UserInfo): Future[Seq[String]] = {
    samDAO.listGroups(userInfo) map { groupMemberships =>
      groupMemberships map (_.groupName) intersect FireCloudConfig.ElasticSearch.discoverGroupNames.asScala
    }
  }

  // this method will determine if the user is making a change to discoverableByGroups
  // if the attribute does not exist on the workspace, it is the same as the empty list
  def isDiscoverableDifferent(workspaceResponse: WorkspaceResponse, userAttrs: AttributeMap): Boolean = {

    def convert(list: Option[Attribute]): Seq[AttributeValue] = {
      list match {
        case Some(x:AttributeValueList) => x.list
        case _ => Seq.empty[AttributeValue]
      }
    }

    val current = convert(workspaceResponse.workspace.attributes.getOrElse(Map.empty).get(LibraryService.discoverableWSAttribute))
    val newvals = convert(userAttrs.get(LibraryService.discoverableWSAttribute))

    current.toSet != newvals.toSet
  }
}
