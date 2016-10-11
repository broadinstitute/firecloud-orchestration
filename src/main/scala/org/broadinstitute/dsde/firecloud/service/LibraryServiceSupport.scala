package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.{AttributeString, Document, RawlsWorkspace}
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import spray.json.{JsArray, JsObject, JsString, JsValue}
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
    * TODO: existing model uses Map[String,String] which will not work with arrays, booleans, etc!
    */
  def generateAttributeOperations(existingAttrs: Map[String, String], newAttrs: JsObject): Seq[AttributeUpdateOperation] = {
    // in this method, ONLY work with "library:" keys, and always ignore the "library:published" key
    val oldKeys = existingAttrs.keySet.filter(k => k.startsWith(libraryPrefix) && !k.equals(libraryPrefix+publishedKey))
    val newFields = newAttrs.fields.filter(k => k._1.startsWith(libraryPrefix) && !k._1.equals(libraryPrefix+publishedKey))

    // remove any attributes that currently exist on the workspace, but are not in the user's packet
    // for any array attributes, we remove them and recreate them entirely. Add the array attrs.
    val keysToRemove = oldKeys.diff(newFields.keySet) ++ newFields.filter(_._2.isInstanceOf[JsArray]).keySet
    val removeOperations = keysToRemove.map(RemoveAttribute(_)).toSeq

    // TODO: handle numeric/boolean values
    val updateOperations = newFields.toSeq flatMap {
      // case (key, value:JsBoolean) => AddUpdateAttribute(key, AttributeString(value.toString()))
      // case (key, value:JsNumber) => AddUpdateAttribute(key, AttributeString(value.toString()))
      case (key, value:JsArray) => value.elements.map{x => AddListMember(key, AttributeString(x.convertTo[String]))}
      case (key, value:JsString) => Seq(AddUpdateAttribute(key, AttributeString(value.convertTo[String])))
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
