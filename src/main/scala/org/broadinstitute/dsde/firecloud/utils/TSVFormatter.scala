package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.model.ModelSchema

import scala.collection.immutable
import scala.util.{Success, Try}

import spray.json.DefaultJsonProtocol._
import spray.json.JsValue

object TSVFormatter {

  def makeMembershipTsvString(entities: List[EntityWithType], entityType: String, collectionMemberType: String): String = {
    val headers: immutable.IndexedSeq[String] = immutable.IndexedSeq("membership:" + entityType + "_id", entityType.replace("_set", "_id"))
    val rows: List[IndexedSeq[String]] = entities.filter { _.entityType == entityType }.flatMap {
      entity =>
        entity.attributes.getOrElse(Map.empty).filter {
          // To make the membership file, we need the array of elements that correspond to the set type.
          // All other top-level properties are not necessary and are only used for the data load file.
          attribute => attribute._1.equals(collectionMemberType) }.flatMap {
          m =>
            Try(m._2.convertTo[List[JsValue]]) match {
              case Success(values) =>
                values map { jsValue =>
                  val cellValue: String = jsValue match {
                    case x if Try(x.asJsObject.fields.contains("entityName")).isSuccess =>
                      cleanValue(x.asJsObject.fields.getOrElse("entityName", x))
                    case _ =>
                      throw new RuntimeException("TSV formatting error, member entity is incorrectly formatted: " + jsValue.toString())
                  }
                  IndexedSeq[String](entity.name, cellValue)
                }
              case _ => IndexedSeq.empty
            }
        }
    }
    exportToString(headers, rows.toIndexedSeq)
  }

  def makeEntityTsvString(entities: List[EntityWithType], entityType: String, requestedHeaders: Option[IndexedSeq[String]]): String = {
    val headerRenamingMap: Map[String, String] = ModelSchema.getAttributeExportRenamingMap(entityType).getOrElse(Map.empty[String, String])
    val entityHeader = "entity:" + entityType + "_id"
    // if we have a set entity, we need to filter out the attribute array of the members so that we only
    // have top-level attributes to construct columns from.
    val memberType = ModelSchema.getCollectionMemberType(entityType)
    val filteredEntities = memberType match {
      case Success(Some(collectionType)) =>
        ModelSchema.getPlural(collectionType) match {
          case Success(attributeName) => filterAttributeFromEntities(entities, attributeName)
          case _ => entities
        }
      case _ => entities
    }

    // entity id always needs to be first and is handled differently so remove it from requestedHeaders
    val requestedHeadersSansId = requestedHeaders.map(_.filterNot(_.equalsIgnoreCase(entityType + "_id")))

    val headers: IndexedSeq[String] = entityHeader +: requestedHeadersSansId.getOrElse(defaultHeaders(entityType, filteredEntities, headerRenamingMap))
    val rows: IndexedSeq[IndexedSeq[String]] = filteredEntities.filter { _.entityType == entityType }
      .map { entity =>
        makeRow(entity, headers, headerRenamingMap)
      }.toIndexedSeq
    exportToString(headers, rows)
  }

  def defaultHeaders(entityType: String, filteredEntities: List[EntityWithType], headerRenamingMap: Map[String, String]) = {
    val attributeNames = filteredEntities.collect {
      case EntityWithType(_, `entityType`, attributes) => attributes.getOrElse(Map.empty).keySet
    }.flatten.distinct

    attributeNames.map { key => headerRenamingMap.getOrElse(key, key) }.toIndexedSeq
  }

  private def exportToString(headers: IndexedSeq[String], rows: IndexedSeq[IndexedSeq[String]]): String = {
    val headerString:String = headers.mkString("\t") + "\n"
    val rowsString:String = rows.map{ _.mkString("\t") }.mkString("\n")
    headerString + rowsString + "\n"
  }

  /**
    * New list of EntityWithType objects with specified attribute filtered out.
    *
    * @param entities Initial list of EntityWithType
    * @return new list of EntityWithType
    */
  private def filterAttributeFromEntities(entities: List[EntityWithType], attributeName: String): List[EntityWithType] = {
    entities map {
      entity =>
        val attributes = entity.attributes.getOrElse(Map.empty) filterNot {
          attribute => attribute._1.equals(attributeName)
        }
        EntityWithType(name = entity.name, entityType = entity.entityType, attributes = Some(attributes))
    }
  }

  /**
    * Generate a row of values in the same order as the headers.
    *
    * @param entity The EntityWithType object to extract data from
    * @param headerValues List of ordered header values to determine order of values
    * @return IndexedSeq of ordered data fields
    */
  private def makeRow(entity: EntityWithType, headerValues: IndexedSeq[String],
    headerRenamingMap:Map[String, String]): IndexedSeq[String] = {
    val rowMap: Map[Int, String] =  entity.attributes.getOrElse(Map.empty) map {
      attribute =>
        val columnPosition = headerValues.indexOf(headerRenamingMap.getOrElse(attribute._1, attribute._1))
        val cellValue = attribute._2 match {
          case x if Try(x.asJsObject.fields.contains("entityName")).isSuccess =>
            cleanValue(x.asJsObject.fields.getOrElse("entityName", x))
          case _ =>
            cleanValue(attribute._2)
        }
        columnPosition -> cellValue
    }
    // If there are entities that don't have a value for which there is a known header, that will
    // be missing in the row. Fill up those positions with empty strings in that case.
    val completedRowMap: IndexedSeq[(Int, String)] =
      IndexedSeq.range(1, headerValues.size).map {
        i => (i, rowMap.getOrElse(i, ""))
      }

    // This rowMap manipulation:
    //  1. sorts the position-value map by the key
    //  2. converts it to a seq of tuples
    //  3. pulls out the second element of the tuple (column value)
    //  4. resulting in a seq of the column values sorted by the column position
    entity.name +: completedRowMap.toSeq.sortBy(_._1).map(_._2).toIndexedSeq
  }

  /**
    * JsValues are double-quoted. Need to remove them before putting them into a cell position
    *
    * @param value The JsValue to remove leading and trailing quotes from.
    * @return Trimmed string value
    */
  def cleanValue(value: JsValue): String = {
    val regex = "^\"|\"$".r
    regex.replaceAllIn(value.toString(), "")
  }

}
