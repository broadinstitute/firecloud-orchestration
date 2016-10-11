package org.broadinstitute.dsde.firecloud.model

import spray.json.JsObject
import spray.json._

/**
  * Created by davidan on 10/11/16.
  */

trait Indexable {
  def id: String
  def content: JsObject
}

case class Document(val id: String, val content: JsObject) extends Indexable {

  def apply(id: String, valMap: Map[String, String]) = {
    val jsfields = valMap.map{ case(k:String, v:String)=>(k, JsString(v)) }
    new Document(id, JsObject(jsfields))
  }
  def apply(id: String, jsonStr: String) = new Document(id, jsonStr.parseJson.asJsObject)
  def apply(id: String, content: JsObject) = new Document(id, content)


}