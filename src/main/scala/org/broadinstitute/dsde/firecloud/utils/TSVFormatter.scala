package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.TsvType

import scala.collection.immutable
import scala.util.{Success, Try}

import spray.json.DefaultJsonProtocol._
import spray.json.JsValue

object TSVFormatter {

  def makeMembershipTsvString(entities: Seq[RawlsEntity], entityType: String, collectionMemberType: String): String = {
    val headers: immutable.IndexedSeq[String] = immutable.IndexedSeq(s"${TsvType.MEMBERSHIP}:${entityType}_id", entityType.replace("_set", "_id"))
    val rows: Seq[IndexedSeq[String]] = entities.filter { _.entityType == entityType }.flatMap {
      entity =>
        entity.attributes.filter {
          // To make the membership file, we need the array of elements that correspond to the set type.
          // All other top-level properties are not necessary and are only used for the data load file.
          case (attributeName, _) => attributeName.equals(AttributeName.withDefaultNS(collectionMemberType))
        }.flatMap {
          case (_, AttributeEntityReference(entityType, entityName)) => Seq(IndexedSeq[String](entity.name, entityName))
          case (_, AttributeEntityReferenceList(refs)) => refs.map( ref => IndexedSeq[String](entity.name, ref.entityName) )
          case _ => Seq.empty
        }
    }
    exportToString(headers, rows.toIndexedSeq)
  }

  def makeEntityTsvString(entities: Seq [RawlsEntity], entityType: String, requestedHeaders: Option[IndexedSeq[String]]): String = {
    val requestedHeadersSansId = requestedHeaders.
      // remove empty strings
      map(_.filter(_.length > 0)).
      // handle empty requested headers as no requested headers
      flatMap(rh => if (rh.isEmpty) None else Option(rh)).
      // entity id always needs to be first and is handled differently so remove it from requestedHeaders
      map(_.filterNot(_.equalsIgnoreCase(entityType + "_id")))

    val entityHeader = requestedHeadersSansId match {
      case Some(headers) if !ModelSchema.getRequiredAttributes(entityType).get.keySet.forall(headers.contains) => s"${TsvType.UPDATE}:${entityType}_id"
      case _ => s"${TsvType.ENTITY}:${entityType}_id"
    }

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

    val headers: IndexedSeq[String] = entityHeader +: requestedHeadersSansId.getOrElse(defaultHeaders(entityType, filteredEntities))
    val rows: IndexedSeq[IndexedSeq[String]] = filteredEntities.filter { _.entityType == entityType }
      .map { entity =>
        makeRow(entity, headers)
      }.toIndexedSeq
    exportToString(headers, rows)
  }

  def defaultHeaders(entityType: String, filteredEntities: Seq[RawlsEntity]) = {
    val attributeNames = filteredEntities.collect {
      case RawlsEntity(_, `entityType`, attributes) => attributes.keySet
    }.flatten.distinct

    attributeNames.map(_.name).toIndexedSeq
  }

  private def exportToString(headers: IndexedSeq[String], rows: IndexedSeq[IndexedSeq[String]]): String = {
    val headerString:String = headers.mkString("\t") + "\n"
    val rowsString:String = rows.map{ _.mkString("\t") }.mkString("\n")
    headerString + rowsString + "\n"
  }

  /**
    * New list of RawlsEntity objects with specified attribute filtered out.
    *
    * @param entities Initial list of RawlsEntity
    * @return new list of RawlsEntity
    */
  private def filterAttributeFromEntities(entities: Seq[RawlsEntity], attributeName: String): Seq[RawlsEntity] = {
    entities map {
      entity =>
        val attributes = entity.attributes filterNot {
          case (thisAttributeName, _) => thisAttributeName == AttributeName.withDefaultNS(attributeName)
        }
        entity.copy(attributes = attributes)
    }
  }

  /**
    * Generate a row of values in the same order as the headers.
    *
    * @param entity The RawlsEntity object to extract data from
    * @param headerValues List of ordered header values to determine order of values
    * @return IndexedSeq of ordered data fields
    */
  private def makeRow(entity: RawlsEntity, headerValues: IndexedSeq[String]): IndexedSeq[String] = {
    val rowMap: Map[Int, String] =  entity.attributes map {
      case (attributeName, attribute) =>
        val columnPosition = headerValues.indexOf(attributeName.name)
        val cellValue = AttributeStringifier(attribute)
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
