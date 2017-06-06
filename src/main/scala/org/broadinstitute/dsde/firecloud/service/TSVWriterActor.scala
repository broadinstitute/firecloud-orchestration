package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import akka.actor.{Actor, Props, _}
import better.files._
import org.broadinstitute.dsde.firecloud.model.ModelSchema
import org.broadinstitute.dsde.firecloud.service.TSVWriterActor._
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter.{filterAttributeFromEntities, makeRow}
import org.broadinstitute.dsde.rawls.model.Entity
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Success

object TSVWriterActor {

  sealed trait TSVWriterMessage
  case class Start() extends TSVWriterMessage
  case class Write(page: Int, entities: Seq[Entity]) extends TSVWriterMessage
  case class End() extends TSVWriterMessage

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
  lazy val finalHeaders: IndexedSeq[String] = makeHeaders(entityType, originalHeaders, requestedHeaders)
  lazy val file: File = File.newTemporaryFile(UUID.randomUUID().toString, ".tsv")

  def receive: Receive = {
    case Start() => val receiver = sender; receiver ! startFile
    case Write(page: Int, entities: Seq[Entity]) => val receiver = sender; receiver ! writeEntities(page, entities)
    case End() => val receiver = sender; receiver ! getFile
  }

  def startFile(): Boolean = {
    log.info("StartFile")
    file.createIfNotExists().overwrite(finalHeaders.mkString("\t") + "\n")
    file.exists
  }

  def writeEntities(page: Int, entities: Seq[Entity]): File = {
    log.info(s"WriteEntities. Appending # entities ${entities.size} to: ${file.path.toString}")
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
    val rows: IndexedSeq[IndexedSeq[String]] = filteredEntities.filter { _.entityType == entityType }
      .map { entity =>
        makeRow(entity, finalHeaders)
      }.toIndexedSeq

    // and finally, write that out to the file.
    file.append(rows.map{ _.mkString("\t") }.mkString("\n")).append("\n")
    file
  }

  def getFile: File = {
    log.info(s"GetFile: Returning file: ${file.path.toString}")
    file
  }

  /**
    * Different header formats for set types vs singular entity types.
    *
    * @param entityType Entity Type
    * @param allHeaders All original headers
    * @param requestedHeaders If requested, then only supply these headers
    * @return IndexedSeq[String] of header strings
    */
  def makeHeaders(entityType: String, allHeaders: Seq[String], requestedHeaders: Option[IndexedSeq[String]]): IndexedSeq[String] = {
    ModelSchema.getCollectionMemberType(entityType) match {
      case Success(Some(collectionType)) =>
        val collectionMemberType: String = ModelSchema.getPlural(collectionType).get
        IndexedSeq[String](s"${TsvTypes.MEMBERSHIP}:${entityType}_id", collectionMemberType)
      case _ =>
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

}
