package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.AttributeFormat
import spray.json._

object Attributable {
  type AttributeMap = Map[AttributeName, Attribute]
}

case class AttributeName(
                          namespace: String,
                          name: String) extends Ordered[AttributeName] {
  // enable implicit ordering for sorting
  import scala.math.Ordered.orderingToOrdered
  def compare(that: AttributeName): Int = (this.namespace, this.name) compare (that.namespace, that.name)
}

object AttributeName {
  val defaultNamespace = "default"
  val libraryNamespace = "library"
  val validNamespaces = Set(AttributeName.defaultNamespace, AttributeName.libraryNamespace)

  val delimiter = ':'

  def withDefaultNS(name: String) = AttributeName(defaultNamespace, name)

  def toDelimitedName(aName: AttributeName): String = {
    if (aName.namespace == defaultNamespace) aName.name
    else aName.namespace + delimiter + aName.name
  }

  def fromDelimitedName(dName: String): AttributeName = {
    dName.split(delimiter).toList match {
      case sName :: Nil => AttributeName.withDefaultNS(sName)
      case sNamespace :: sName :: Nil => AttributeName(sNamespace, sName)
      case _ => throw new FireCloudException(s"Attribute string $dName has too many '$delimiter' delimiters")
    }
  }
}

case object AttributeValueRawJson {
  def apply(str: String) : AttributeValueRawJson = AttributeValueRawJson(str.parseJson)
}

sealed trait Attribute
sealed trait AttributeListElementable extends Attribute //terrible name for "this type can legally go in an attribute list"
sealed trait AttributeValue extends AttributeListElementable
sealed trait AttributeList[T <: AttributeListElementable] extends Attribute { val list: Seq[T] }
case object AttributeNull extends AttributeValue
case class AttributeString(val value: String) extends AttributeValue
case class AttributeNumber(val value: BigDecimal) extends AttributeValue
case class AttributeBoolean(val value: Boolean) extends AttributeValue
case class AttributeValueRawJson(val value: JsValue) extends AttributeValue
case object AttributeValueEmptyList extends AttributeList[AttributeValue] { val list = Seq.empty }
case object AttributeEntityReferenceEmptyList extends AttributeList[AttributeEntityReference] { val list = Seq.empty }
case class AttributeValueList(val list: Seq[AttributeValue]) extends AttributeList[AttributeValue]
case class AttributeEntityReferenceList(val list: Seq[AttributeEntityReference]) extends AttributeList[AttributeEntityReference]
case class AttributeEntityReference(val entityType: String, val entityName: String) extends AttributeListElementable

object AttributeStringifier {
  def apply(attribute: Attribute): String = {
    attribute match {
      case AttributeNull => ""
      case AttributeString(value) => value
      case AttributeNumber(value) => value.toString()
      case AttributeBoolean(value) => value.toString()
      case AttributeValueRawJson(value) => value.toString()
      case AttributeEntityReference(t, name) => name
      case al: AttributeList[_] =>
        val format = new AttributeFormat with PlainArrayAttributeListSerializer
        format.write(al).toString()
    }
  }
}
