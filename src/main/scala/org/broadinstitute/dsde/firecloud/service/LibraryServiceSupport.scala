package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{OntologyDAO, RawlsDAO}
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.rawls.model.Attributable._
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.firecloud.model._
import org.everit.json.schema.{Schema, ValidationException}
import org.everit.json.schema.loader.SchemaLoader
import org.json.{JSONObject, JSONTokener}
import org.parboiled.common.FileUtils

import scala.collection.JavaConversions._
import spray.json.{JsBoolean, JsObject, JsString}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by davidan on 10/2/16.
  */
trait LibraryServiceSupport {

  def updatePublishAttribute(value: Boolean): Seq[AttributeUpdateOperation] = {
    if (value) Seq(AddUpdateAttribute(LibraryService.publishedFlag, AttributeBoolean(true)))
    else Seq(RemoveAttribute(LibraryService.publishedFlag))
  }

  def indexableDocument(workspace: Workspace, ontologyDAO: OntologyDAO): Document = {
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
      AttributeName.withDefaultNS("workspaceId") -> AttributeString(workspace.workspaceId)
    )
    Document(workspace.workspaceId, attrfields ++ idfields)
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

}
