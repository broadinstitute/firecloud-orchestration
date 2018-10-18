package org.broadinstitute.dsde.firecloud.model

import scala.io.Source
import scala.util.{Failure, Success, Try}
import spray.json._
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException}


/**
 * Created with IntelliJ IDEA.
 * User: hussein
 * Date: 07/24/2015
 * Time: 14:18
 */

trait ModelSchema {
  def memberTypeFromEntityType (entityType: String): Try[Option[String]]
  def isCollectionType (entityType: String): Boolean
  def getPlural (entityType: String): Try[String]
  def getRequiredAttributes(entityType: String): Try[Map[String, String]]
  def getTypeSchema(entityType: String): Try[EntityMetadata]
  def supportsBackwardsCompatibleIds(): Boolean

  def isEntityTypeInSchema(entityType: String): Boolean = {
    Try(this.memberTypeFromEntityType(entityType)) match {
      case Failure(_) => false
      case _ => true
    }
  }
}



object SchemaTypes {
  sealed trait SchemaType
  final case object FIRECLOUD extends SchemaType { override def toString = "firecloud" }
  final case object FLEXIBLE extends SchemaType { override def toString = "flexible" }

  def withName(name: String): SchemaType = {
    name.toLowerCase match {
      case "firecloud" => FIRECLOUD
      case "flexible" => FLEXIBLE
      case _ => throw new FireCloudException(s"Invalid schema type '$name', supported types are: firecloud, flexible")
    }
  }
}



object ModelSchemaRegistry {
  // add new schema types from most specific to most general
  val schemas: Map[SchemaTypes.SchemaType, ModelSchema] = Map(SchemaTypes.FIRECLOUD -> new FirecloudModelSchema, SchemaTypes.FLEXIBLE -> new FlexibleModelSchema)

  def getModelForSchemaType(schemaType: SchemaTypes.SchemaType): ModelSchema = schemas.getOrElse(schemaType, schemas.last._2)

  def getModelForEntityType(entityType: String): ModelSchema = {
    val possibleMatch = schemas.values.collectFirst({case schema if schema.isEntityTypeInSchema(entityType) => schema})
    possibleMatch.getOrElse(schemas.last._2)
  }
}


class FlexibleModelSchema extends ModelSchema {

  def memberTypeFromEntityType(entityType: String): Try[Option[String]] = {
    Success(Some(entityType.replace("_set", "")).filter(_ => isCollectionType(entityType)))
  }

  def isCollectionType(entityType: String): Boolean = {
    entityType.endsWith("_set")
  }

  def getRequiredAttributes(entityType: String): Try[Map[String, String]] = {
    Success(Map.empty)
  }

  def getPlural(entityType: String): Try[String] =  {
    Success(entityType + "s")
  }

  def getTypeSchema(entityType: String): Try[EntityMetadata] = {
    Success(EntityMetadata(entityType, Map.empty, Some(entityType+"_members").filter(_ => isCollectionType(entityType))))
  }

  def supportsBackwardsCompatibleIds(): Boolean = false
}

class FirecloudModelSchema extends ModelSchema {

  object EntityTypes {
    val types : Map[String, EntityMetadata] = ModelJsonProtocol.impModelSchema.read(
      Source.fromURL(getClass.getResource(FireCloudConfig.Rawls.model)).mkString.parseJson ).schema
  }

  def getTypeSchema(entityType: String): Try[EntityMetadata] = {
    EntityTypes.types.get(entityType) map (Success(_)) getOrElse(Failure(new RuntimeException("Unknown entity type: " + entityType)))
  }

  def memberTypeFromEntityType(entityType: String): Try[Option[String]] = {
    getTypeSchema(entityType).map(_.memberType)
  }

  def getRequiredAttributes(entityType: String): Try[Map[String, String]] = {
    getTypeSchema(entityType).map(_.requiredAttributes)
  }

  def isCollectionType(entityType: String): Boolean = {
    EntityTypes.types.get(entityType) map (_.memberType.isDefined) getOrElse(false)
  }

  def getPlural(entityType: String): Try[String] = {
    getTypeSchema(entityType).map(_.plural)
  }

  def supportsBackwardsCompatibleIds(): Boolean = true
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

