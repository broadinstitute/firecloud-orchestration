package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, _}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{
  PerRequestMessage,
  RequestComplete,
  RequestCompleteWithHeaders
}
import org.broadinstitute.dsde.firecloud.utils.{PermissionsSupport, TSVFormatter, TSVLoadFile, TSVParser}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{
  AddListMember,
  AddUpdateAttribute,
  AttributeUpdateOperation,
  RemoveListMember
}
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.rawls.model._
import java.time.Instant
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.math.BigDecimal.RoundingMode
import scala.util.{Failure, Success, Try}

/**
  * Created by mbemis on 10/19/16.
  */
object WorkspaceService {
  def constructor(app: Application)(userToken: WithAccessToken)(implicit executionContext: ExecutionContext) =
    new WorkspaceService(userToken, app.rawlsDAO, app.samDAO, app.thurloeDAO, app.googleServicesDAO)
}

class WorkspaceService(protected val argUserToken: WithAccessToken,
                       val rawlsDAO: RawlsDAO,
                       val samDao: SamDAO,
                       val thurloeDAO: ThurloeDAO,
                       val googleServicesDAO: GoogleServicesDAO
)(implicit protected val executionContext: ExecutionContext)
    extends AttributeSupport
    with TSVFileSupport
    with PermissionsSupport
    with SprayJsonSupport
    with LazyLogging {

  implicit val userToken: WithAccessToken = argUserToken

  val storagePriceList = FireCloudConfig.GoogleCloud.storagePriceList

  def getStorageCostEstimateV2(workspaceNamespace: String,
                               workspaceName: String
  ): Future[RequestComplete[WorkspaceStorageUsageAndCostEstimate]] =
    (for {
      bucketUsage <- rawlsDAO.getBucketUsageV2(workspaceNamespace, workspaceName)
    } yield {
      // Convert bytes to GB since rate is based on GB.
      val (totalBytes, totalEstimate) = bucketUsage.metrics.foldLeft((BigDecimal(0), BigDecimal(0))) {
        case ((sumBytes, sumEstimate), metric) =>
          val bytes = BigDecimal(metric.valueInBytes)
          val estimate = bytes / (1024 * 1024 * 1024) * storagePriceList(metric.storageClass)
          (sumBytes + metric.valueInBytes, sumEstimate + estimate)
      }
      RequestComplete(
        WorkspaceStorageUsageAndCostEstimate(totalEstimate.setScale(2, RoundingMode.HALF_UP),
                                             totalBytes.toBigInt,
                                             Instant.now
        )
      )
    }) recoverWith {
      case e: NoSuchElementException =>
        Future.failed(
          new FireCloudExceptionWithErrorReport(
            ErrorReport(message = s"Unrecognized storage class found: ${e}")
          )
        )
      case e: Throwable =>
        Future.failed(
          new FireCloudExceptionWithErrorReport(
            ErrorReport(message = s"Error fetching bucket storage metrics: ${e.getMessage}")
          )
        )
    }

  def setWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) =
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
      // this is technically vulnerable to a race condition in which the workspace attributes have changed
      // between the time we retrieved them and here, where we update them.
      val allOperations = generateAttributeOperations(workspaceResponse.workspace.attributes.getOrElse(Map.empty),
                                                      newAttributes,
                                                      _.namespace != AttributeName.libraryNamespace
      )
      for {
        ws <- rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, allOperations)
      } yield RequestComplete(ws)
    }

  def updateWorkspaceACL(workspaceNamespace: String,
                         workspaceName: String,
                         aclUpdates: Seq[WorkspaceACLUpdate],
                         originEmail: String,
                         originId: String,
                         inviteUsersNotFound: Boolean
  ): Future[RequestComplete[WorkspaceACLUpdateResponseList]] = {

    val aclUpdate = rawlsDAO.patchWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, inviteUsersNotFound)

    aclUpdate map { actualUpdates =>
      RequestComplete(actualUpdates)
    }
  }

  def exportWorkspaceAttributesTSV(workspaceNamespace: String,
                                   workspaceName: String,
                                   filename: String
  ): Future[PerRequestMessage] =
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) map { workspaceResponse =>
      val attributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer
      val attributes = workspaceResponse.workspace.attributes
        .getOrElse(Map.empty)
        .view
        .filterKeys(_ != AttributeName.withDefaultNS("description"))
      val headerString =
        "workspace:" + (attributes map { case (attName, _) => attName.name }).mkString(s"${TSVParser.DELIMITER}")
      val valueString = (attributes map { case (_, attValue) => TSVFormatter.tsvSafeAttribute(attValue) })
        .mkString(s"${TSVParser.DELIMITER}")
      // TODO: entity TSVs are downloaded as text/tab-separated-value, but workspace attributes are text/plain. Align these?
      RequestCompleteWithHeaders(
        (StatusCodes.OK, headerString + "\n" + valueString),
        `Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> filename)),
        `Content-Type`(ContentTypes.`text/plain(UTF-8)`)
      )
    }

  def importAttributesFromTSV(workspaceNamespace: String,
                              workspaceName: String,
                              tsvString: String
  ): Future[PerRequestMessage] =
    withTSVFile(tsvString) { tsv =>
      tsv.firstColumnHeader.split(":")(0) match {
        case "workspace" =>
          importWorkspaceAttributeTSV(workspaceNamespace, workspaceName, tsv)
        case _ =>
          Future.successful(
            RequestCompleteWithErrorReport(StatusCodes.BadRequest,
                                           "Invalid TSV. First column header should start with \"workspace\""
            )
          )
      }
    }

  private def importWorkspaceAttributeTSV(workspaceNamespace: String,
                                          workspaceName: String,
                                          tsv: TSVLoadFile
  ): Future[PerRequestMessage] =
    checkNumberOfRows(tsv, 2) {
      checkFirstRowDistinct(tsv) {
        rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
          Try(getWorkspaceAttributeCalls(tsv)) match {
            case Failure(regret) =>
              Future.successful(
                RequestCompleteWithErrorReport(StatusCodes.BadRequest,
                                               "One or more of your values are not in the correct format"
                )
              )
            case Success(attributeCalls) =>
              rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, attributeCalls) map (RequestComplete(
                _
              ))
          }
        }
      }
    }

  def getTags(workspaceNamespace: String, workspaceName: String): Future[PerRequestMessage] =
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
      val tags = getTagsFromWorkspace(workspaceResponse.workspace)
      Future(RequestComplete(StatusCodes.OK, formatTags(tags)))
    }

  def putTags(workspaceNamespace: String, workspaceName: String, tags: List[String]): Future[PerRequestMessage] = {
    val attrList = AttributeValueList(tags map (tag => AttributeString(tag.trim)))
    val op = AddUpdateAttribute(AttributeName.withTagsNS(), attrList)
    patchAndRepublishWorkspace(workspaceNamespace, workspaceName, Seq(op))
  }

  private def patchAndRepublishWorkspace(workspaceNamespace: String,
                                         workspaceName: String,
                                         ops: Seq[AttributeUpdateOperation]
  ) =
    for {
      ws <- rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, ops)
      // TODO CORE-382: can this be a passthrough?
    } yield {
      val tags = getTagsFromWorkspace(ws)
      RequestComplete(StatusCodes.OK, formatTags(tags))
    }

  def patchTags(workspaceNamespace: String, workspaceName: String, tags: List[String]): Future[PerRequestMessage] =
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { origWs =>
      val origTags = getTagsFromWorkspace(origWs.workspace)
      val attrOps =
        (tags diff origTags) map (tag => AddListMember(AttributeName.withTagsNS(), AttributeString(tag.trim)))
      patchAndRepublishWorkspace(workspaceNamespace, workspaceName, attrOps)
    }

  def deleteTags(workspaceNamespace: String, workspaceName: String, tags: List[String]): Future[PerRequestMessage] = {
    val attrOps = tags map (tag => RemoveListMember(AttributeName.withTagsNS(), AttributeString(tag.trim)))
    patchAndRepublishWorkspace(workspaceNamespace, workspaceName, attrOps)
  }

  private def getTagsFromWorkspace(ws: WorkspaceDetails): Seq[String] =
    ws.attributes.getOrElse(Map.empty).get(AttributeName.withTagsNS()) match {
      case Some(vals: AttributeValueList) =>
        vals.list collect { case s: AttributeString =>
          s.value
        }
      case _ => Seq.empty[String]
    }

  private def formatTags(tags: Seq[String]) = tags.toList.sortBy(_.toLowerCase)

}
