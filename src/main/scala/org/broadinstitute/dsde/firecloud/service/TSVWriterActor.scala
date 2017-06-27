package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import akka.actor.{Actor, Props, _}
import better.files._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.service.TSVWriterActor._
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter._
import org.broadinstitute.dsde.rawls.model.Entity

object TSVWriterActor {

  sealed trait TSVWriterMessage
  case class WriteEntityTSV(page: Int, entities: Seq[Entity]) extends TSVWriterMessage
  case class WriteMembershipTSV(page: Int, entities: Seq[Entity]) extends TSVWriterMessage

  case class WriteTSVFile(entityType: String, originalHeaders: Seq[String], requestedHeaders: Option[IndexedSeq[String]], pages: Int) extends TSVWriterActor

  def props(entityType: String, headers: Seq[String], requestedHeaders: Option[IndexedSeq[String]], pages: Int): Props =
    Props(WriteTSVFile(entityType, headers, requestedHeaders, pages))

}

trait TSVWriterActor extends Actor with LazyLogging {

  def entityType: String
  def originalHeaders: Seq[String]
  def requestedHeaders: Option[IndexedSeq[String]]
  def pages: Int

  lazy val file: File = File.newTemporaryFile(UUID.randomUUID().toString, ".tsv")

  def receive: Receive = {
    case WriteEntityTSV(page: Int, entities: Seq[Entity]) => sender ! writeEntityTSV(page, entities)
    case WriteMembershipTSV(page: Int, entities: Seq[Entity]) => sender ! writeMembershipTSV(page, entities)
  }

  def writeMembershipTSV(page: Int, entities: Seq[Entity]): File = {
    if (page == 0) {
      val headers = makeMembershipHeaders(entityType)
      file.createIfNotExists().overwrite(headers.mkString("\t") + "\n")
    }
    val rows: Seq[IndexedSeq[String]] = makeMembershipRows(entityType, entities)
    file.append(rows.map{ _.mkString("\t") }.mkString("\n")).append("\n")
    file
  }

  def writeEntityTSV(page: Int, entities: Seq[Entity]): File = {
    val headers = makeEntityHeaders(entityType, originalHeaders, requestedHeaders)
    if (page == 0) {
      file.createIfNotExists().overwrite(headers.mkString("\t") + "\n")
    }
    val rows = makeEntityRows(entityType, entities, headers)
    file.append(rows.map{ _.mkString("\t") }.mkString("\n")).append("\n")
    file
  }

}