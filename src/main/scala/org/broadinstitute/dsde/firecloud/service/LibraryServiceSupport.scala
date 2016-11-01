package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.firecloud.model._
import org.everit.json.schema.Schema
import org.everit.json.schema.loader.SchemaLoader
import org.json.{JSONObject, JSONTokener}
import org.parboiled.common.FileUtils
import spray.json.JsObject

/**
  * Created by davidan on 10/2/16.
  */
trait LibraryServiceSupport extends AttributeSupport {
  def generateAttributeOperations(existingAttrs: AttributeMap, newAttrs: AttributeMap): Seq[AttributeUpdateOperation] = {
    generateAttributeOperations(existingAttrs, newAttrs, k => k.namespace == AttributeName.libraryNamespace && k.name != LibraryService.publishedFlag.name)
  }

  def updatePublishAttribute(value: Boolean): Seq[AttributeUpdateOperation] = {
    if (value) Seq(AddUpdateAttribute(LibraryService.publishedFlag, AttributeBoolean(true)))
    else Seq(RemoveAttribute(LibraryService.publishedFlag))
  }

  def indexableDocument(workspace: RawlsWorkspace): Document = {
    val attrfields = workspace.attributes.filter(_._1.namespace == AttributeName.libraryNamespace)
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

}
