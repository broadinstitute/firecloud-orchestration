package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.model.ModelSchema
import spray.json.JsValue

import scala.collection.{immutable, mutable}
import scala.util.Try

object TSVFormatter {

  def makeTsvString(entities: List[EntityWithType], entityType: String) = {
    val headerRenamingMap: Map[String, String] = ModelSchema.getAttributeRenamingMap(entityType).getOrElse(Map.empty[String, String])
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
   * Generate a row of values in the order of the headers.
   *
   * @param entity The EntityWithType object to extract data from
   * @param headerValues List of header values to determine column position for
   * @return The ordered data fields in an IndexedSeq
   */
  private def makeRow(entity: EntityWithType, headerValues: IndexedSeq[String], headerRenamingMap:Map[String, String]): IndexedSeq[String] = {
    val row = mutable.IndexedSeq().padTo(headerValues.size, "")
    row.update(0, entity.name)
    entity.attributes.getOrElse(Map.empty).foreach { e =>
      val columnPosition = headerValues.indexOf(headerRenamingMap.getOrElse(e._1, e._1))
      makeCellValue(row, columnPosition, e._2)
    }
    row.toIndexedSeq
  }

  /**
   * When adding a cell to a row, we need to check if the entry is a JSObject. In that case,
   * we have to parse the value as a json structure and look for "entityName"
   *
   * @param row The row to update
   * @param columnPosition The column position of the inserted value
   * @param value The value to insert/parse for real value
   */
  private def makeCellValue(
    row: mutable.IndexedSeq[String],
    columnPosition: Int,
    value: JsValue) = {
      value match {
        case y if Try(y.asJsObject.fields.contains("entityName")).isSuccess =>
          row.update(
            columnPosition,
            cleanValue(value.asJsObject.fields.getOrElse("entityName", value)))
        case _ =>
          row.update(columnPosition, cleanValue(value))
      }
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
