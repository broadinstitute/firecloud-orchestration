package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.AttributeString
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import spray.json.{JsArray, JsObject, JsString, JsValue}
import spray.json.DefaultJsonProtocol._

/**
  * Created by davidan on 10/2/16.
  */
trait LibraryServiceSupport {

  /**
    * given a set of existing attributes and a set of new attributes, calculate the attribute operations
    * that need to be performed
    * TODO: existing model uses Map[String,String] which will not work with arrays, booleans, etc!
    * TODO: define how to handle "library:" prefix - is that sent in the inbound request?
    */
  def generateAttributeOperations(existingAttrs: Map[String, String], newAttrs: JsObject): Seq[AttributeUpdateOperation] = {
    val libraryAttrKeys = existingAttrs.keySet

    // remove any attributes that currently exist on the workspace, but are not in the user's packet
    // for any array attributes, we remove them and recreate them entirely. Add the array attrs.
    val keysToRemove = libraryAttrKeys.diff(newAttrs.fields.keySet) ++ newAttrs.fields.filter(_._2.isInstanceOf[JsArray]).keySet
    val removeOperations = keysToRemove.map(RemoveAttribute(_)).toSeq

    // TODO: handle numeric/boolean values
    val updateOperations = newAttrs.fields.toSeq flatMap {
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
    val operations: Seq[AttributeUpdateOperation] =
    if (value) Seq(AddUpdateAttribute("library:published", AttributeString("true")))
    else Seq(RemoveAttribute("library:published"))

    operations
  }

}
