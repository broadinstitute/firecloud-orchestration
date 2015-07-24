package org.broadinstitute.dsde.firecloud.model

/**
 * Created by tsharpe on 7/28/15.
 */
sealed trait Attribute
case class AttributeString(value: String) extends Attribute
case class AttributeReference(entityType: String, entityName: String) extends Attribute
case class EntityUpdateDefinition( name: String,
                                   entityType: String,
                                   operations: Seq[Map[String,Attribute]] )
