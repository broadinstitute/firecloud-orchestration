package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.TsvTypes

object TSVFormatter {

  // for serializing entity references
  val attributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer

  /**
    * Generate file content from headers and rows.
    *
    * @param headers IndexedSeq of header string values
    * @param rows IndexedSeq of rows, each row an IndexedSeq of string values
    * @return Headers and rows combined.
    */
  def exportToString(headers: IndexedSeq[String], rows: IndexedSeq[IndexedSeq[String]]): String = {
    val headerString:String = headers.mkString("\t") + "\n"
    val rowsString:String = rows.map{ _.mkString("\t") }.mkString("\n")
    headerString + rowsString + "\n"
  }

  /**
    * New list of Entity objects with specified attribute filtered out.
    *
    * @param entities Initial list of Entity
    * @return new list of Entity
    */
  private def filterAttributeFromEntities(entities: Seq[Entity], attributeName: String): Seq[Entity] = {
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
    * @param entity The Entity object to extract data from
    * @param headerValues List of ordered header values to determine order of values
    * @return IndexedSeq of ordered data fields
    */
  private def makeRow(entity: Entity, headerValues: IndexedSeq[String]): IndexedSeq[String] = {
    val rowMap: Map[Int, String] =  entity.attributes map {
      case (attributeName, attribute) =>
        val columnPosition = headerValues.indexOf(AttributeName.toDelimitedName(attributeName))
        val cellValue = tsvSafeAttribute(attribute)
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
    entity.name +: completedRowMap.sortBy(_._1).map(_._2).toIndexedSeq
  }

  /**
    * Given an Attribute, creates a string that is safe to output into a TSV as a cell value.
    * - if the input attribute contains a tab character, then double-quote it
    *
    * @param value The input attribute to make safe
    * @return the safe value
    */
  def tsvSafeAttribute(attribute: Attribute): String = {
    // AttributeStringifier works for everything except single entity references;
    // it even works for AttributeEntityReferenceList
    val intermediateString = attribute match {
      case ref:AttributeEntityReference => attributeFormat.write(ref).compactPrint
      case _ => AttributeStringifier(attribute)
    }
    tsvSafeString(intermediateString)
  }

  /**
    * Creates a string that is safe to output into a TSV as a cell value.
    * - if the input string contains a tab character, then double-quote it
    *
    * @param value The input value to make safe
    * @return the safe value
    */
  def tsvSafeString(value: String): String = {
    if (value.contains(TSVParser.DELIMITER)) {
      s"\"$value\""
    } else {
      value
    }
  }

  /**
    * Generate a header for a membership file.
    *
    * @param entityType The EntityType
    * @return IndexedSeq of header Strings
    */
  def makeMembershipHeaders(entityType: String)(implicit modelSchema: ModelSchema): IndexedSeq[String] = {
    IndexedSeq[String](s"${TsvTypes.MEMBERSHIP}:${entityType}_id", modelSchema.getCollectionMemberType(entityType).get.getOrElse(
      entityType.replace("_set", "")))
  }

  /**
    * Prepare an ordered list of row data for a membership file
    *
    * @param entityType Display name for the type of entity (e.g. "participant")
    * @param entities The Entity objects to convert to rows.
    * @return Ordered list of rows
    */
  def makeMembershipRows(entityType: String, entities: Seq[Entity])(implicit modelSchema: ModelSchema): Seq[IndexedSeq[String]] = {
    val memberPlural = pluralizeMemberType(memberTypeFromEntityType(entityType, modelSchema), modelSchema)
      entities.filter {
        _.entityType == entityType
      }.flatMap {
        entity =>
          entity.attributes.filter {
            // To make the membership file, we need the array of elements that correspond to the set type.
            // All other top-level properties are not necessary and are only used for the data load file.
            case (attributeName, _) => attributeName.equals(AttributeName.withDefaultNS(memberPlural))
          }.flatMap {
            case (_, AttributeEntityReference(`entityType`, entityName)) => Seq(IndexedSeq[String](entity.name, entityName))
            case (_, AttributeEntityReferenceList(refs)) => refs.map(ref => IndexedSeq[String](entity.name, ref.entityName))
            case _ => Seq.empty
          }
      }
  }

  /**
    * Prepare an ordered list of headers (column labels)
    *
    * @param entityType Display name for the type of entity (e.g. "participant")
    * @param allHeaders The universe of available column headers
    * @param requestedHeaders Which, if any, columns were requested. If none, return allHeaders (subject to sanitization)
    * @return Entity name as first column header, followed by matching entity attribute labels
    */
  def makeEntityHeaders(entityType: String, allHeaders: Seq[String], requestedHeaders: Option[IndexedSeq[String]])(implicit modelSchema: ModelSchema): IndexedSeq[String] = {
    // will throw exception if firecloud model was requested and the entity type
    val memberPlural = pluralizeMemberType(memberTypeFromEntityType(entityType, modelSchema), modelSchema)

    val requestedHeadersSansId = requestedHeaders.
      // remove empty strings
      map(_.filter(_.length > 0)).
      // handle empty requested headers as no requested headers
      flatMap(rh => if (rh.isEmpty) None else Option(rh)).
      // entity id always needs to be first and is handled differently so remove it from requestedHeaders
      map(_.filterNot(_.equalsIgnoreCase(entityType + "_id"))).
      // filter out member attribute if a set type
      map { h => if (modelSchema.isCollectionType(entityType)) h.filterNot(_.equals(memberPlural)) else h }

    val filteredAllHeaders = if (modelSchema.isCollectionType(entityType)) {
      allHeaders.filterNot(_.equals(memberPlural))
    } else {
      allHeaders
    }

    val requiredAttrsTry = modelSchema.getRequiredAttributes(entityType)
    val entityHeader: String = requestedHeadersSansId match {
        // if not all required fields are requested, then this tsv is an update
      case Some(headers) if requiredAttrsTry.isSuccess && !requiredAttrsTry.get.keySet.forall(headers.contains) => s"${TsvTypes.UPDATE}:${entityType}_id"
      case _ => s"${TsvTypes.ENTITY}:${entityType}_id"
    }
    (entityHeader +: requestedHeadersSansId.getOrElse(filteredAllHeaders)).toIndexedSeq
  }

  /**
    * Prepare an ordered list of row data
    *
    * @param entityType Display name for the type of entity (e.g. "participant")
    * @param entities The Entity objects to convert to rows.
    * @param headers The universe of available column headers
    * @return Ordered list of rows, each row entry value ordered by its corresponding header position
    */
  def makeEntityRows(entityType: String, entities: Seq[Entity], headers: IndexedSeq[String])(implicit modelSchema: ModelSchema): IndexedSeq[IndexedSeq[String]] = {
    // if we have a set entity, we need to filter out the attribute array of the members so that we only
    // have top-level attributes to construct columns from.
    val filteredEntities = if (modelSchema.isCollectionType(entityType)) {
      val memberPlural = pluralizeMemberType(memberTypeFromEntityType(entityType, modelSchema), modelSchema)
      filterAttributeFromEntities(entities, memberPlural)
    } else {
      entities
    }
    // Turn them into rows
    filteredEntities
      .filter { _.entityType == entityType }
      .map { entity => makeRow(entity, headers) }
      .toIndexedSeq
  }

  def memberTypeFromEntityType(entityType: String, modelSchema: ModelSchema): String = modelSchema.getCollectionMemberType(entityType).get.getOrElse(entityType.replace("_set", ""))
  def pluralizeMemberType(memberType: String, modelSchema: ModelSchema): String = modelSchema.getPlural(memberType).getOrElse(memberType + "s")
  def isCollectionType(entityType: String, modelSchema: ModelSchema): Boolean = modelSchema.isCollectionType(entityType)
}
