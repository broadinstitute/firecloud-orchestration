package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.model.ModelSchema

import scala.collection.immutable
import scala.util.Try

import spray.json.DefaultJsonProtocol._
import spray.json.JsValue

object TSVFormatter {

  def makeMembershipTsvString(entities: List[EntityWithType], entityType: String): String = {
    val headers: immutable.IndexedSeq[String] = immutable.IndexedSeq("membership:" + entityType + "_id", entityType.replace("_set", "_id"))
    val rows: List[IndexedSeq[String]] = entities filter { _.entityType == entityType } flatMap { entity =>
      entity.attributes.getOrElse(Map.empty) flatMap { m =>
        m._2.convertTo[List[JsValue]] map { jsValue =>
          val cellValue: String = jsValue match {
            case x if Try(x.asJsObject.fields.contains("entityName")).isSuccess =>
              cleanValue(x.asJsObject.fields.getOrElse("entityName", x))
            case _ =>
              ""
          }
          IndexedSeq[String](entity.name, cellValue)
        }
      }
    }
    exportToString(headers, rows.toIndexedSeq)
  }

  def makeEntityTsvString(entities: List[EntityWithType], entityType: String): String = {
    val headerRenamingMap: Map[String, String] = ModelSchema.getAttributeExportRenamingMap(entityType).getOrElse(Map.empty[String, String])
    val entityHeader = "entity:" + entityType + "_id"
    val headers: immutable.IndexedSeq[String] = entityHeader +: entities.
      filter { _.entityType == entityType }.
      map { _.attributes.getOrElse(Map.empty) }.
      flatMap(_.keySet).
      distinct.
      map { key => headerRenamingMap.getOrElse(key, key) }.
      toIndexedSeq
    val rows: immutable.IndexedSeq[IndexedSeq[String]] = entities.filter { _.entityType == entityType }
      .map { entity =>
        makeRow(entity, headers, headerRenamingMap)
      }.toIndexedSeq
    exportToString(headers, rows)
  }

  private def exportToString(headers: IndexedSeq[String], rows: IndexedSeq[IndexedSeq[String]]): String = {
    val headerString:String = headers.mkString("\t") + "\n"
    val rowsString:String = rows.map{ _.mkString("\t") }.mkString("\n")
    headerString + rowsString + "\n"
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
    // The rowMap manipulation sorts the position-value map by the key, converts it to a seq of
    // tuples, pulls out the second element of the tuple (column value) resulting in a sorted seq
    // of the original map values.
    entity.name +: rowMap.toSeq.sortBy(_._1).map(_._2).toIndexedSeq
  }

  /**
   * JsValues are double-quoted. Need to remove them before putting them into a cell position
   *
   * @param value The JsValue to remove leading and trailing quotes from.
   * @return Trimmed string value
   */
  private def cleanValue(value: JsValue): String = {
    val regex = "^\"|\"$".r
    regex.replaceAllIn(value.toString(), "")
  }

}
