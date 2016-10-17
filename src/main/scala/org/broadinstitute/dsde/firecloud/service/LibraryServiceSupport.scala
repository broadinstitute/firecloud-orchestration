package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import spray.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString, JsValue}
import spray.json.DefaultJsonProtocol._

/**
  * Created by davidan on 10/2/16.
  */
trait LibraryServiceSupport {

  final val libraryPrefix = "library:"
  final val publishedKey = "published"

  /**
    * given a set of existing attributes and a set of new attributes, calculate the attribute operations
    * that need to be performed
    */
  def generateAttributeOperations[T](existingAttrs: AttributeMap, newAttrs: AttributeMap): Seq[AttributeUpdateOperation] = {
    // in this method, ONLY work with "library:" keys, and always ignore the "library:published" key
    val oldKeys = existingAttrs.keySet.filter(k => k.namespace == AttributeName.libraryNamespace && !(k.name == publishedKey))
    val newFields = newAttrs.seq.filter(k => k._1.namespace == AttributeName.libraryNamespace && !(k._1.name == publishedKey))

    // remove any attributes that currently exist on the workspace, but are not in the user's packet
    // for any array attributes, we remove them and recreate them entirely. Add the array attrs.
    val keysToRemove: Set[AttributeName] = oldKeys.diff(newFields.keySet) ++ newFields.filter(_._2.isInstanceOf[JsArray]).keySet
    val removeOperations = keysToRemove.map(RemoveAttribute).toSeq

    val updateOperations = newFields.toSeq flatMap {
      case (key, value:AttributeValue) => Seq(AddUpdateAttribute(key, value))
      case (key, value:AttributeEntityReference) => Seq(AddUpdateAttribute(key, value))

      case (key, value:AttributeList[T]) => Seq() //map, like below
      //we don't need to check jsobject structure is valid any more because there's no jsobjects. that's done in deserialization

      //handle lists. we don't need to check the elems are self-similar here because that's handled when they're popupated
      case (key, value:JsArray) => value.elements.map{x => AddListMember(key, AttributeString(x.convertTo[String]))}

      case (key, value:JsValue) => Seq(AddUpdateAttribute(key, AttributeString(value.toString))) // .toString on a JsString includes extra quotes
    }

    // handle removals before upserts
    (removeOperations ++ updateOperations)
  }

  def updatePublishAttribute(value: Boolean): Seq[AttributeUpdateOperation] = {
    // TODO: publish attribute can just be a boolean once we support boolean attributes
    if (value) Seq(AddUpdateAttribute(LibraryService.publishedFlag, AttributeString("true")))
    else Seq(RemoveAttribute(LibraryService.publishedFlag))
  }

  // TODO: support for boolean, numeric, array attributes
  def indexableDocument(workspace: RawlsWorkspace): Document = {
    val attrfields = workspace.attributes.filter(_._1.startsWith("library:"))
    val idfields = Map(
      "name" -> workspace.name,
      "namespace" -> workspace.namespace,
      "workspaceId" -> workspace.workspaceId
    )
    Document(workspace.workspaceId, attrfields ++ idfields)
  }

}
