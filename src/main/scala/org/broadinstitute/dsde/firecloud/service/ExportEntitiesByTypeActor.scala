package org.broadinstitute.dsde.firecloud.service

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.{Actor, Props}
import akka.pattern.pipe
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo}
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.ExportEntities
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.http._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

object ExportEntitiesByTypeActor {
  sealed trait ExportEntitiesByTypeMessage
  case class ExportEntities(workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]) extends ExportEntitiesByTypeMessage

  def props(exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor, userInfo: UserInfo): Props = {
    Props(exportEntitiesByTypeConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new ExportEntitiesByTypeActor(app.rawlsDAO, userInfo)
}

class ExportEntitiesByTypeActor(val rawlsDAO: RawlsDAO, val userInfo: UserInfo)(implicit protected val executionContext: ExecutionContext) extends Actor with ExportEntitiesByType {
  override def receive: Receive = {
    case ExportEntities(workspaceNamespace, workspaceName, filename, entityType, attributeNames) => exportEntities(workspaceNamespace, workspaceName, filename, entityType, attributeNames) pipeTo sender
  }
}

trait ExportEntitiesByType extends FireCloudRequestBuilding {
  val rawlsDAO: RawlsDAO
  implicit val userInfo: UserInfo
  implicit protected val executionContext: ExecutionContext

  def exportEntities(workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[PerRequestMessage] = {
    rawlsDAO.fetchAllEntitiesOfType(workspaceNamespace, workspaceName, entityType) map { entities =>
      ModelSchema.getCollectionMemberType(entityType) match {
        case Success(Some(collectionType)) =>
          val collectionMemberType = ModelSchema.getPlural(collectionType)
          val entityData = TSVFormatter.makeEntityTsvString(entities, entityType, attributeNames)
          val membershipData = TSVFormatter.makeMembershipTsvString(entities, entityType, collectionMemberType.get)
          val zipBytes: Array[Byte] = getZipBytes(entityType, membershipData, entityData)
          val zippedFileName = entityType + ".zip"
          RequestCompleteWithHeaders(
            HttpEntity(ContentTypes.`application/octet-stream`, zipBytes),
            HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> zippedFileName)))
        case _ =>
          val data = TSVFormatter.makeEntityTsvString(entities, entityType, attributeNames)
          RequestCompleteWithHeaders(
            (OK, data),
            HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> filename)),
            HttpHeaders.`Content-Type`(`text/plain`))
      }
    }
  }

  private def getZipBytes(entityType: String, membershipData: String, entityData: String): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(bos)
    zos.putNextEntry(new ZipEntry(entityType + "_membership.txt"))
    zos.write(membershipData.getBytes)
    zos.closeEntry()
    zos.putNextEntry(new ZipEntry(entityType + "_entity.txt"))
    zos.write(entityData.getBytes)
    zos.closeEntry()
    zos.finish()
    bos.toByteArray
  }

}
