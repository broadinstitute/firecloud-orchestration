package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import spray.json.JsValue

import scala.collection.{immutable, mutable}

object EntityMatrix {

  def makeTsvString(entities: List[EntityWithType], entityType: String) = {
    val entityHeader = "entity:" + entityType
    val headers: immutable.IndexedSeq[String] = entityHeader +: entities.
      filter { _.entityType == entityType }.
      map { _.attributes.getOrElse(Map.empty) }.
      flatMap(_.keySet).
      distinct.
      map { trimmedHeaderValue }.
      toIndexedSeq
    val rows: immutable.IndexedSeq[IndexedSeq[String]] = entities.filter { _.entityType == entityType }
      .map { entity =>
        makeRow(entity, headers)
      }.toIndexedSeq
    exportToString(headers, rows)
  }

  private def exportToString(headers: IndexedSeq[String], rows: IndexedSeq[IndexedSeq[String]]): String = {
    val headerString:String = headers.mkString("\t") + "\n"
    val rowsString:String = rows.map{ _.mkString("\t") }.mkString("\n")
    headerString + rowsString + "\n"
  }

  /**
   * All headers suffixed with "_id" need to have that suffix dropped
   *
   * @param value Value to check
   * @return Trimmed value
   */
  private def trimmedHeaderValue(value:String): String = {
    value match {
      case x if x.endsWith("_id") => x dropRight 3
      case x => x
    }
  }

  /**
   * Generate a row of values in the order of the headers.
   *
   * @param entity The EntityWithType object to extract data from
   * @param headerValues List of header values to determine column position for
   * @return The ordered data fields in an IndexedSeq
   */
  private def makeRow(entity: EntityWithType, headerValues: IndexedSeq[String]): IndexedSeq[String] = {
    val row = mutable.IndexedSeq().padTo(headerValues.size, "")
    row.update(0, entity.name)
    entity.attributes.getOrElse(Map.empty).foreach { e =>
      val columnPosition = headerValues.indexOf(trimmedHeaderValue(e._1))
      makeCellValue(row, columnPosition, e._1, e._2)
    }
    row.toIndexedSeq
  }

  /**
   * When adding a cell to a row, we need to check if the entry is an "_id" field. In that case,
   * we have to parse the value as a json structure and look for "entityName"
   *
   * @param row The row to update
   * @param columnPosition The column position of the inserted value
   * @param header The header to check. "_id" headers require special processing
   * @param value The value to insert/parse for real value
   */
  private def makeCellValue(
    row: mutable.IndexedSeq[String],
    columnPosition: Int,
    header: String,
    value: JsValue) = {
    header match {
      case x if x.endsWith("_id") || x.equals("case_sample") || x.equals("control_sample") =>
        value match {
          case y if y.asJsObject.fields.contains("entityName") =>
            row.update(
              columnPosition,
              cleanJsValue(value.asJsObject.fields.getOrElse("entityName", value))
            )
          case _ =>
            row.update(columnPosition, cleanJsValue(value))
        }
      case x =>
        row.update(columnPosition, cleanJsValue(value))
    }
  }

  /**
   * JsValues are double-quoted. Need to remove them before putting them into a cell position
   *
   * @param value The JsValue to remove leading and trailing quotes from.
   * @return Trimmed string value
   */
  private def cleanJsValue(value: JsValue): String = {
    val regex = "^\"|\"$".r
    regex.replaceAllIn(value.toString(), "")
  }

}
