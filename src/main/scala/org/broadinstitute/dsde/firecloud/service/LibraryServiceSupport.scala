package org.broadinstitute.dsde.firecloud.service

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{OntologyDAO, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Ontology.TermParent
import org.broadinstitute.dsde.firecloud.model.{Document, ElasticSearch, LibrarySearchResponse, UserInfo}
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.rawls.model._
import org.everit.json.schema.loader.SchemaLoader
import org.everit.json.schema.{Schema, ValidationException}
import org.json.{JSONObject, JSONTokener}
import org.parboiled.common.FileUtils
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
  * Created by davidan on 10/2/16.
  */
trait LibraryServiceSupport extends DataUseRestrictionSupport with LazyLogging {

  def updatePublishAttribute(value: Boolean): Seq[AttributeUpdateOperation] = {
    if (value) Seq(AddUpdateAttribute(LibraryService.publishedFlag, AttributeBoolean(true)))
    else Seq(RemoveAttribute(LibraryService.publishedFlag))
  }

  // TODO: this doesn't have to be a future, leaving it as a Future for compatibility
  def indexableDocuments(workspaces: Seq[Workspace], ontologyDAO: OntologyDAO)(implicit ec: ExecutionContext): Future[Seq[Document]] = {
    // find all the ontology nodes in this list of workspaces
    val nodesSeq:Seq[String] = workspaces.collect {
        case w if w.attributes.contains(AttributeName.withLibraryNS("diseaseOntologyID")) =>
          w.attributes(AttributeName.withLibraryNS("diseaseOntologyID"))
      }.collect {
        case s:AttributeString => s.value
      }
    logger.debug(s"found ${nodesSeq.size} workspaces with ontology nodes assigned")

    val nodes = nodesSeq.toSet
    logger.debug(s"found ${nodes.size} unique ontology nodes")

    // query ontology for this set of nodes, save in a map
    val parentCache = nodes map {id => (id, lookupParentNodes(id, ontologyDAO))}

    // using the cached parent information, build the indexable documents
    val parentMap = parentCache.toMap.filter(e => e._2.nonEmpty) // remove nodes that have no parent
    logger.debug(s"have parent results for ${parentMap.size} ontology nodes")
    val docsResult: Seq[Document] = workspaces map {w => indexableDocument(w, parentMap)}

    Future(docsResult)

  }

  private def indexableDocument(workspace: Workspace, parentCache: Map[String,Seq[TermParent]])(implicit ec: ExecutionContext): Document = {
    val attrfields_subset = workspace.attributes.filter(_._1.namespace == AttributeName.libraryNamespace)
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
      AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(workspace.authorizationDomain.map(group => AttributeString(group.membersGroupName.value)).toSeq)
    )

    val tagfields = workspace.attributes.get(AttributeName.withTagsNS()) match {
      case Some(t) => Map(AttributeName.withTagsNS() -> t)
      case None => Map()
    }

    val durAttributeNames = allDurFieldNames.map(AttributeName.withLibraryNS)
    val dur: Map[AttributeName, Attribute] = generateStructuredUseRestrictionAttribute(workspace)
    val displayDur: Map[AttributeName, Attribute] = generateUseRestrictionDisplayAttribute(workspace)

    val fields = (attrfields -- durAttributeNames) ++ idfields ++ tagfields ++ dur ++ displayDur

    workspace.attributes.get(AttributeName.withLibraryNS("diseaseOntologyID")) match {
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
      (ve.getCausingExceptions flatMap getSchemaValidationMessages)
  }

  def getEffectiveDiscoverGroups(rawlsDAO: RawlsDAO)(implicit ec: ExecutionContext, userInfo:UserInfo): Future[Seq[String]] = {
    rawlsDAO.getGroupsForUser map {FireCloudConfig.ElasticSearch.discoverGroupNames intersect _}
  }

  def updateAccess(docs: LibrarySearchResponse, workspaces: Seq[WorkspaceListResponse]) = {

    val accessMap = workspaces map { workspaceResponse: WorkspaceListResponse =>
      workspaceResponse.workspace.workspaceId -> workspaceResponse.accessLevel
    } toMap

    val updatedResults = docs.results.map { document =>
      val docId = document.asJsObject.fields.get("workspaceId")
      val newJson = docId match {
        case Some(id: JsString) => accessMap.get(id.value) match {
          case Some(accessLevel) => document.asJsObject.fields + ("workspaceAccess" -> JsString(accessLevel.toString))
          case _ => document.asJsObject.fields + ("workspaceAccess" -> JsString(WorkspaceAccessLevels.NoAccess.toString))
        }
        case _ => document.asJsObject.fields
      }
      JsObject(newJson)
    }

    docs.copy(results = updatedResults)
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

    val current = convert(workspaceResponse.workspace.attributes.get(LibraryService.discoverableWSAttribute))
    val newvals = convert(userAttrs.get(LibraryService.discoverableWSAttribute))

    current.toSet != newvals.toSet
  }
}
