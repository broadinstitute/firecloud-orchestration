package org.broadinstitute.dsde.firecloud.model

import java.io.FileReader
import java.nio.file.{Paths, Files}

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.parboiled.common.FileUtils
import spray.json._

import scala.util.{Failure, Success, Try}

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
    Files.readAllLines(Paths.get(FireCloudConfig.Workspace.model)).toArray.mkString.parseJson ).schema
}

case class EntityMetadata(
  plural: String,                         //Used to name the members attribute of collection types, e.g. sample_set.samples
  requiredAttributes: Map[String, String],//(Attribute name -> stringified type) Might be an entity type defined by the ModelSchema.
  memberType: Option[String]              //If defined, we're a collection type, and this is the entity type of our members
                           )

case class EntityModel(schema : Map[String, EntityMetadata]) //entity name -> stuff about it
