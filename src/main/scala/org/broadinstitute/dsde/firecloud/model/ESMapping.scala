package org.broadinstitute.dsde.firecloud.model

case class AttributeDefinition(properties: Map[String, AttributeDetail])
case class AttributeDetail(`type`: String, items: Option[AttributeDetail]=None)

case class ESDatasetProperty(properties: Map[String, ESDetail])
case class ESDetail(`type`: String)
