package org.broadinstitute.dsde.firecloud.model

import scala.io.Source
import spray.json._
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException}

import scala.util.{Failure, Try}

/**
 * Created with IntelliJ IDEA.
 * User: hussein
 * Date: 07/24/2015
 * Time: 14:18
 */

trait ModelSchema {
  def memberTypeFromEntityType (entityType: String): Option[String]
  def isCollectionType (entityType: String): Boolean
  def getPlural (entityType: String): String
  def getRequiredAttributes(entityType: String): Map[String, String]
  def getTypeSchema(entityType: String): EntityMetadata

  def isEntityTypeInSchema(entityType: String): Boolean = {
    Try(this.memberTypeFromEntityType(entityType)) match {
      case Failure(_) => false
      case _ => true
    }
  }
}

object SchemaFactory {
  // add new schema types from most specific to most general
  val schemas: Seq[ModelSchema] = Seq(new FirecloudModelSchema, new FlexibleModelSchema)
  def getSchemaForEntityType(entityType: String): ModelSchema = {
    val possibleMatch = schemas.collectFirst({case schema if schema.isEntityTypeInSchema(entityType) => schema})
    possibleMatch.getOrElse(schemas.last)
  }
}


class FlexibleModelSchema extends ModelSchema {

  def memberTypeFromEntityType(entityType: String): Option[String] = {
    isCollectionType(entityType) match {
      case true => Some(entityType.replace("_set", ""))
      case _ => None
    }
  }

  def isCollectionType(entityType: String): Boolean = {
    entityType.endsWith("_set")
  }

  def getRequiredAttributes(entityType: String): Map[String, String] = {
    Map.empty
  }

  def getPlural(entityType: String): String =  {
    entityType + "s"
  }

  def getTypeSchema(entityType: String): EntityMetadata = {
    isCollectionType(entityType) match {
      case true => EntityMetadata(entityType+"s", Map.empty, Some(entityType+"_members"))
      case _ => EntityMetadata(entityType+"s", Map.empty, None)
    }
  }
}

class FirecloudModelSchema extends ModelSchema {

  object EntityTypes {
    val types : Map[String, EntityMetadata] = ModelJsonProtocol.impModelSchema.read(
      Source.fromURL(getClass.getResource(FireCloudConfig.Rawls.model)).mkString.parseJson ).schema
  }

  def getTypeSchema(entityType: String): EntityMetadata = {
    EntityTypes.types.getOrElse(entityType, throw new FireCloudException(("Unknown entity type: " + entityType)))
  }

  def memberTypeFromEntityType(entityType: String): Option[String] = {
    getTypeSchema(entityType).memberType
  }

  def getRequiredAttributes(entityType: String): Map[String, String] = {
    getTypeSchema(entityType).requiredAttributes
  }

  def isCollectionType(entityType: String): Boolean = {
    EntityTypes.types.get(entityType) match {
      case Some(schema) => schema.memberType.isDefined
      case None => entityType.endsWith("_set")
    }
  }

  def getPlural(entityType: String): String = {
    getTypeSchema(entityType).plural
  }

  def isUsingFirecloudModelSchema(entityType: String): Boolean = {
    EntityTypes.types.get(entityType).isDefined
  }
}

/**
 * Metadata for entities in our model
 *
 * @param plural Used to name the members attribute of collection types, e.g. sample_set.samples
 * @param requiredAttributes (Attribute name -> stringified type) Might be an entity type defined by the ModelSchema.
 * @param memberType If defined, we're a collection type, and this is the entity type of our members
 */
case class EntityMetadata(
  plural: String,
  requiredAttributes: Map[String, String],
  memberType: Option[String]
)

case class EntityModel(schema : Map[String, EntityMetadata]) //entity name -> stuff about it

