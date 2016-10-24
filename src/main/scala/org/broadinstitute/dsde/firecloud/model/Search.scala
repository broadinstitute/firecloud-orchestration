package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import spray.json.JsObject
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

trait Indexable {
  def id: String
  def content: JsObject
}

case class Document(val id: String, val content: JsObject) extends Indexable

object Document {
  def apply(id: String, valMap: AttributeMap) = {
    new Document(id, valMap.toJson.asJsObject)
  }
  def apply(id: String, jsonStr: String) = new Document(id, jsonStr.parseJson.asJsObject)
}
