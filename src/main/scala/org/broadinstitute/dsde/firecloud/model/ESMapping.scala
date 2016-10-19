package org.broadinstitute.dsde.firecloud.model

case class AttributeDefinition(properties: Map[String, AttributeDetail])
case class AttributeDetail(`type`: String)

case class ESMapping(mappings: Map[String, ESProperty])
case class ESProperty(properties: ESDetail)
case class ESDetail(`type`: String)
