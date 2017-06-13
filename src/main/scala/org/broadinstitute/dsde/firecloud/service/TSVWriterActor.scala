package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import akka.actor.{Actor, Props, _}
import better.files._
import org.broadinstitute.dsde.firecloud.model.ModelSchema
import org.broadinstitute.dsde.firecloud.service.TSVWriterActor._
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter.{filterAttributeFromEntities, makeRow, makeEntityHeaders}
import org.broadinstitute.dsde.rawls.model.{AttributeEntityReference, AttributeEntityReferenceList, AttributeName, Entity}
import org.slf4j.{Logger, LoggerFactory}

object TSVWriterActor {

  sealed trait TSVWriterMessage
  case class WriteEntityTSV(page: Int, entities: Seq[Entity]) extends TSVWriterMessage
  case class WriteMembershipTSV(page: Int, entities: Seq[Entity]) extends TSVWriterMessage

  case class WriteTSVFile(entityType: String, originalHeaders: Seq[String], requestedHeaders: Option[IndexedSeq[String]], pages: Int) extends TSVWriterActor

  def props(entityType: String, headers: Seq[String], requestedHeaders: Option[IndexedSeq[String]], pages: Int): Props =
    Props(WriteTSVFile(entityType, headers, requestedHeaders, pages))

}

// TODO: Move a lot of the utility functions to TSVFormatter so they can be separately tested
trait TSVWriterActor extends Actor {

  def entityType: String
  def originalHeaders: Seq[String]
  def requestedHeaders: Option[IndexedSeq[String]]
  def pages: Int

  lazy val memberType: String = ModelSchema.getCollectionMemberType(entityType).get.getOrElse(entityType.replace("_set", ""))
  lazy val memberPlural: String = ModelSchema.getPlural(memberType).getOrElse(memberType + "s")
  lazy val isCollectionType: Boolean = ModelSchema.isCollectionType(entityType).getOrElse(false)
  lazy val log: Logger = LoggerFactory.getLogger(getClass)
  lazy val file: File = File.newTemporaryFile(UUID.randomUUID().toString, ".tsv")

  def receive: Receive = {
    case WriteEntityTSV(page: Int, entities: Seq[Entity]) => val receiver = sender; receiver ! writeEntityTSV(page, entities)
    case WriteMembershipTSV(page: Int, entities: Seq[Entity]) => val receiver = sender; receiver ! writeMembershipTSV(page, entities)
  }

  def writeMembershipTSV(page: Int, entities: Seq[Entity]): File = {
    if (page == 0) {
      log.info("WriteMembershipTSV: creating file with headers.")
      val headers = makeMembershipHeaders(entityType, originalHeaders, requestedHeaders)
      file.createIfNotExists().overwrite(headers.mkString("\t") + "\n")
    }
    log.info(s"WriteMembershipTSV. Appending ${entities.size} entities to: ${file.path.toString}")
    val rows: Seq[IndexedSeq[String]] = entities.filter { _.entityType == entityType }.flatMap {
      entity =>
        entity.attributes.filter {
          // To make the membership file, we need the array of elements that correspond to the set type.
          // All other top-level properties are not necessary and are only used for the data load file.
          case (attributeName, _) => attributeName.equals(AttributeName.withDefaultNS(memberPlural))
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
    IndexedSeq[String](s"${TsvTypes.MEMBERSHIP}:${entityType}_id", memberType)
  }

  def writeEntityTSV(page: Int, entities: Seq[Entity]): File = {
    val headers = makeEntityHeaders(entityType, originalHeaders, requestedHeaders, isCollectionType, memberPlural)
    if (page == 0) {
      log.info("WriteEntityTSV: creating file with headers.")
      file.createIfNotExists().overwrite(headers.mkString("\t") + "\n")
    }
    log.info(s"WriteEntityTSV. Appending ${entities.size} entities to: ${file.path.toString}")
    // if we have a set entity, we need to filter out the attribute array of the members so that we only
    // have top-level attributes to construct columns from.
    val filteredEntities = isCollectionType match {
      case x if x => filterAttributeFromEntities(entities, memberPlural)
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

}
