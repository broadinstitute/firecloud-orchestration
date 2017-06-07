package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import akka.actor.{Actor, Props, _}
import better.files._
import org.broadinstitute.dsde.firecloud.model.ModelSchema
import org.broadinstitute.dsde.firecloud.service.TSVWriterActor._
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter.{filterAttributeFromEntities, makeRow}
import org.broadinstitute.dsde.rawls.model.{AttributeEntityReference, AttributeEntityReferenceList, AttributeName, Entity}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Success

object TSVWriterActor {

  sealed trait TSVWriterMessage
  case class WriteEntityTSV(page: Int, entities: Seq[Entity]) extends TSVWriterMessage
  case class WriteMembershipTSV(page: Int, entities: Seq[Entity]) extends TSVWriterMessage

  case class WriteTSVFile(entityType: String, originalHeaders: Seq[String], requestedHeaders: Option[IndexedSeq[String]], pages: Int) extends TSVWriterActor

  def props(entityType: String, headers: Seq[String], requestedHeaders: Option[IndexedSeq[String]], pages: Int): Props =
    Props(WriteTSVFile(entityType, headers, requestedHeaders, pages))

}

trait TSVWriterActor extends Actor {

  def entityType: String
  def originalHeaders: Seq[String]
  def requestedHeaders: Option[IndexedSeq[String]]
  def pages: Int

  lazy val log: Logger = LoggerFactory.getLogger(getClass)
  lazy val file: File = File.newTemporaryFile(UUID.randomUUID().toString, ".tsv")

  def receive: Receive = {
    case WriteEntityTSV(page: Int, entities: Seq[Entity]) => val receiver = sender; receiver ! writeEntityTSV(page, entities)
    case WriteMembershipTSV(page: Int, entities: Seq[Entity]) => val receiver = sender; receiver ! writeMembershipTSV(page, entities)
  }

  def writeMembershipTSV(page: Int, entities: Seq[Entity]): File = {
    if (page == 0) {
      log.debug("WriteEntities: creating file with headers.")
      val headers = makeMembershipHeaders(entityType, originalHeaders, requestedHeaders)
      file.createIfNotExists().overwrite(headers.mkString("\t") + "\n")
    }
    val rows: Seq[IndexedSeq[String]] = entities.filter { _.entityType == entityType }.flatMap {
      entity =>
        entity.attributes.filter {
          // To make the membership file, we need the array of elements that correspond to the set type.
          // All other top-level properties are not necessary and are only used for the data load file.
          case (attributeName, _) => attributeName.equals(AttributeName.withDefaultNS(""))
        }.flatMap {
          case (_, AttributeEntityReference(entityType, entityName)) => Seq(IndexedSeq[String](entity.name, entityName))
          case (_, AttributeEntityReferenceList(refs)) => refs.map( ref => IndexedSeq[String](entity.name, ref.entityName) )
          case _ => Seq.empty
        }
    }
    file.append(rows.map{ _.mkString("\t") }.mkString("\n")).append("\n")
    file
  }

  private def makeMembershipHeaders(entityType: String, allHeaders: Seq[String], requestedHeaders: Option[IndexedSeq[String]]): IndexedSeq[String] = {
    val memberType: String = ModelSchema.getCollectionMemberType(entityType).get.getOrElse(entityType.replace("_set", ""))
    IndexedSeq[String](s"${TsvTypes.MEMBERSHIP}:${entityType}_id", memberType)
  }

  def writeEntityTSV(page: Int, entities: Seq[Entity]): File = {
    val headers = makeEntityHeaders(entityType, originalHeaders, requestedHeaders)
    if (page == 0) {
      log.debug("WriteEntities: creating file with headers.")
      file.createIfNotExists().overwrite(headers.mkString("\t") + "\n")
    }
    log.debug(s"WriteEntities. Appending ${entities.size} entities to: ${file.path.toString}")
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

    // Turn them into rows
    val rows: IndexedSeq[IndexedSeq[String]] = filteredEntities
      .filter { _.entityType == entityType }
      .map { entity => makeRow(entity, headers) }
      .toIndexedSeq
    // and finally, write that out to the file.
    file.append(rows.map{ _.mkString("\t") }.mkString("\n")).append("\n")
    file
  }

  private def makeEntityHeaders(entityType: String, allHeaders: Seq[String], requestedHeaders: Option[IndexedSeq[String]]): IndexedSeq[String] = {
    val requestedHeadersSansId = requestedHeaders.
      // remove empty strings
      map(_.filter(_.length > 0)).
      // handle empty requested headers as no requested headers
      flatMap(rh => if (rh.isEmpty) None else Option(rh)).
      // entity id always needs to be first and is handled differently so remove it from requestedHeaders
      map(_.filterNot(_.equalsIgnoreCase(entityType + "_id")))
    val entityHeader: String = requestedHeadersSansId match {
      case Some(headers) if !ModelSchema.getRequiredAttributes(entityType).get.keySet.forall(headers.contains) => s"${TsvTypes.UPDATE}:${entityType}_id"
      case _ => s"${TsvTypes.ENTITY}:${entityType}_id"
    }
    (entityHeader +: requestedHeadersSansId.getOrElse(allHeaders)).toIndexedSeq
  }


}
