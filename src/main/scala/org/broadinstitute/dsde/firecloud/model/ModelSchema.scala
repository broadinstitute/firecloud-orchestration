package org.broadinstitute.dsde.firecloud.model

import scala.io.Source
import scala.util.{Failure, Success, Try}
import spray.json._
import org.broadinstitute.dsde.firecloud.FireCloudConfig

/**
 * Created with IntelliJ IDEA.
 * User: hussein
 * Date: 07/24/2015
 * Time: 14:18
 */

object ModelSchema {


  def getTypeSchema(entityType: String): Try[EntityMetadata] = {
    EntityTypes.types.get(entityType) match {
      case Some(schema) => Success(schema)
      case None => Failure(new RuntimeException("Unknown entity type: " + entityType))
    }
  }

  def isCollectionType(entityType: String): Try[Boolean] = {
    getTypeSchema(entityType).map(_.memberType.isDefined)
  }

  def getCollectionMemberType(collectionEntityType: String): Try[Option[String]] = {
    getTypeSchema(collectionEntityType).map(_.memberType)
  }

  def getPlural(entityType: String): Try[String] = {
    getTypeSchema(entityType).map(_.plural)
  }

  def getRequiredAttributes(entityType: String): Try[Map[String, String]] = {
    getTypeSchema(entityType).map(_.requiredAttributes)
  }
}

object EntityTypes {
  val types : Map[String, EntityMetadata] = ModelJsonProtocol.impModelSchema.read(
    Source.fromURL(getClass.getResource(FireCloudConfig.Rawls.model)).mkString.parseJson ).schema
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

object FlexibleModelSchema {
  def memberTypeFromEntityType(entityType: String): Try[String] = {
    val memberType = EntityTypes.types.get(entityType) match {
      case Some(schema) => schema.memberType
      case None => if (entityType.endsWith("_set"))
        Some(entityType.replace("_set", "")) else
        None
    }
    Try(memberType.get)
  }

  def pluralizeEntityType(entityType: String): String =  {
    EntityTypes.types.get(entityType) match {
      case Some(schema) => schema.plural
      case None => entityType + "s"
    }
  }

//  def getCollectionMemberType(collectionEntityType: String): Option[String] = {
//    EntityTypes.types.get(collectionEntityType) match {
//      case Some(schema) => schema.memberType
//      case None => collectionEntityType.endsWith("_set")
//    }
//    getTypeSchema(collectionEntityType).map(_.memberType)
//  }

  def isCollectionType(entityType: String): Boolean = {
    EntityTypes.types.get(entityType) match {
      case Some(schema) => schema.memberType.isDefined
      case None => entityType.endsWith("_set")
    }
  }

  def isUsingFirecloudSchema(entityType: String): Boolean = {
    EntityTypes.types.get(entityType).isDefined
  }

}