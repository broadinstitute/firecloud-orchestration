package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import spray.json.JsArray
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impAttributeFormat

/**
  * Created by davidan on 10/2/16.
  */
trait LibraryServiceSupport {
  /**
    * given a set of existing attributes and a set of new attributes, calculate the attribute operations
    * that need to be performed
    */
  def generateAttributeOperations(existingAttrs: AttributeMap, newAttrs: AttributeMap): Seq[AttributeUpdateOperation] = {
    // in this method, ONLY work with "library:" keys, and always ignore the "library:published" key
    val oldKeys = existingAttrs.keySet.filter( k => k.namespace == AttributeName.libraryNamespace && !(k.name == LibraryService.publishedFlag.name))
    val newFields = newAttrs.seq.filter(k => k._1.namespace == AttributeName.libraryNamespace && !(k._1.name == LibraryService.publishedFlag.name))

    // remove any attributes that currently exist on the workspace, but are not in the user's packet
    // for any array attributes, we remove them and recreate them entirely. Add the array attrs.
    val keysToRemove: Set[AttributeName] = oldKeys.diff(newFields.keySet) ++ newFields.filter( _._2.isInstanceOf[AttributeList[_]] ).keySet
    val removeOperations = keysToRemove.map(RemoveAttribute).toSeq

    val updateOperations = newFields.toSeq flatMap {
      case (key, value:AttributeValue) => Seq(AddUpdateAttribute(key, value))
      case (key, value:AttributeEntityReference) => Seq(AddUpdateAttribute(key, value))

      case (key, value:AttributeList[Attribute @unchecked]) => value.list.map(x => AddListMember(key, x))
    }

    // handle removals before upserts
    (removeOperations ++ updateOperations)
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

}
