package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.EntityService._
import org.broadinstitute.dsde.firecloud.dataaccess.LegacyFileTypes.FILETYPE_RAWLS
import org.broadinstitute.dsde.firecloud.dataaccess.{CwdsDAO, GoogleServicesDAO, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, _}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TsvTypes.TsvType
import org.broadinstitute.dsde.firecloud.service.{TSVFileSupport, TsvTypes}
import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName}
import spray.json.DefaultJsonProtocol._

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object EntityService {

  def constructor(app: Application)(modelSchema: ModelSchema)(implicit executionContext: ExecutionContext) =
    new EntityService(app.rawlsDAO, app.cwdsDAO, app.googleServicesDAO, modelSchema)

  def colNamesToAttributeNames(headers: Seq[String], requiredAttributes: Map[String, String]): Seq[(String, Option[String])] = {
    headers.tail map { colName => (colName, requiredAttributes.get(colName))}
  }

  def backwardsCompatStripIdSuffixes(tsvLoadFile: TSVLoadFile, entityType: String, modelSchema: ModelSchema): TSVLoadFile = {
    modelSchema.getTypeSchema(entityType) match {
      case Failure(_) => tsvLoadFile // the failure will be handled during parsing
      case Success(metaData) =>
        val newHeaders = tsvLoadFile.headers.map { header =>
          val headerSansId = header.stripSuffix("_id")
          if (metaData.requiredAttributes.keySet.contains(headerSansId) || metaData.memberType.contains(headerSansId)) {
            headerSansId
          } else {
            header
          }
        }
        tsvLoadFile.copy(headers = newHeaders)
    }
  }

}

class EntityService(rawlsDAO: RawlsDAO, cwdsDAO: CwdsDAO, googleServicesDAO: GoogleServicesDAO, modelSchema: ModelSchema)(implicit val executionContext: ExecutionContext)
  extends TSVFileSupport with LazyLogging {

  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  /**
   * Returns the plural form of the entity type.
   * Bails with a 400 Bad Request if the entity type is unknown to the schema and we are using firecloud model
   * If using flexible model, just appends an 's' */
  private def withPlural(entityType: String)(op: String => Future[PerRequestMessage]): Future[PerRequestMessage] = {
    modelSchema.getPlural(entityType) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
      case Success(plural) => op(plural)
    }
  }


  /**
   * Verifies that the provided list of headers includes all attributes required by the schema for this entity type.
   * Bails with a 400 Bad Request if the entity type is unknown or if some attributes are missing.
   * Returns the list of required attributes if all is well. */
  private def withRequiredAttributes(entityType: String, headers: Seq[String])(op: Map[String, String] => Future[PerRequestMessage]):Future[PerRequestMessage] = {
    modelSchema.getRequiredAttributes(entityType) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
      case Success(requiredAttributes) =>
        if( !requiredAttributes.keySet.subsetOf(headers.toSet) ) {
          Future( RequestCompleteWithErrorReport(BadRequest,
            "TSV is missing required attributes: " + (requiredAttributes.keySet -- headers).mkString(", ")) )
        } else {
          op(requiredAttributes)
        }
    }
  }

  /**
   * Imports collection members into a collection type entity. */
  private def importMembershipTSV(
    workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, entityType: String, userInfo: UserInfo, isAsync: Boolean ): Future[PerRequestMessage] = {
    withMemberCollectionType(entityType, modelSchema) { memberTypeOpt =>
      validateMembershipTSV(tsv, memberTypeOpt) {
        withPlural(memberTypeOpt.get) { memberPlural =>
          val rawlsCalls = (tsv.tsvData groupBy(_.head) map { case (entityName, rows) =>
            val ops = rows map { row =>
              //row(1) is the entity to add as a member of the entity in row.head
              val attrRef = AttributeEntityReference(memberTypeOpt.get,row(1))
              Map(addListMemberOperation,"attributeListName"->AttributeString(memberPlural),"newMember"->attrRef)
            }
            EntityUpdateDefinition(entityName,entityType,ops)
          }).toSeq
          maybeAsyncBatchUpdate(isAsync, true, workspaceNamespace, workspaceName, entityType, rawlsCalls, userInfo)
        }
      }
    }
  }

  /**
   * Creates or updates entities from an entity TSV. Required attributes must exist in column headers. */
  private def importEntityTSV(
    workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, entityType: String, userInfo: UserInfo, isAsync: Boolean, deleteEmptyValues: Boolean ): Future[PerRequestMessage] = {
    //we're setting attributes on a bunch of entities
    checkFirstColumnDistinct(tsv) {
      withMemberCollectionType(entityType, modelSchema) { memberTypeOpt =>
        checkNoCollectionMemberAttribute(tsv, memberTypeOpt) {
          withRequiredAttributes(entityType, tsv.headers) { requiredAttributes =>
            val colInfo = colNamesToAttributeNames(tsv.headers, requiredAttributes)
            val rawlsCalls = tsv.tsvData.map(row => setAttributesOnEntity(entityType, memberTypeOpt, row, colInfo, modelSchema, deleteEmptyValues))
            maybeAsyncBatchUpdate(isAsync, true, workspaceNamespace, workspaceName, entityType, rawlsCalls, userInfo)
          }
        }
      }
    }
  }

  /**
   * Updates existing entities from TSV. All entities must already exist. */
  private def importUpdateTSV(
    workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, entityType: String, userInfo: UserInfo, isAsync: Boolean, deleteEmptyValues: Boolean ): Future[PerRequestMessage] = {
    //we're setting attributes on a bunch of entities
    checkFirstColumnDistinct(tsv) {
      withMemberCollectionType(entityType, modelSchema) { memberTypeOpt =>
        checkNoCollectionMemberAttribute(tsv, memberTypeOpt) {
          modelSchema.getRequiredAttributes(entityType) match {
            //Required attributes aren't required to be headers in update TSVs - they should already have been
            //defined when the entity was created. But we still need the type information if the headers do exist.
            case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
            case Success(requiredAttributes) =>
              val colInfo = colNamesToAttributeNames(tsv.headers, requiredAttributes)
              val rawlsCalls = tsv.tsvData.map(row => setAttributesOnEntity(entityType, memberTypeOpt, row, colInfo, modelSchema, deleteEmptyValues))
              maybeAsyncBatchUpdate(isAsync, false, workspaceNamespace, workspaceName, entityType, rawlsCalls, userInfo)
          }
        }
      }
    }
  }

  private def maybeAsyncBatchUpdate(isAsync: Boolean, isUpsert: Boolean, workspaceNamespace: String, workspaceName: String,
                                    entityType: String, rawlsCalls: Seq[EntityUpdateDefinition], userInfo: UserInfo): Future[PerRequestMessage] = {
    if (isAsync) {
      asyncImport(workspaceNamespace, workspaceName, isUpsert, rawlsCalls, userInfo).recover {
        case e: Exception =>
          RequestCompleteWithErrorReport(InternalServerError, "Unexpected error during async TSV import", e)
      }
    } else {
      val rawlsResponse = if (isUpsert) {
        rawlsDAO.batchUpsertEntities(workspaceNamespace, workspaceName, entityType, rawlsCalls)(userInfo)
      } else {
        rawlsDAO.batchUpdateEntities(workspaceNamespace, workspaceName, entityType, rawlsCalls)(userInfo)
      }
      handleBatchRawlsResponse(entityType, rawlsResponse)
    }
  }

  private def asyncImport(workspaceNamespace: String, workspaceName: String, isUpsert: Boolean,
                          rawlsCalls: Seq[EntityUpdateDefinition], userInfo: UserInfo): Future[PerRequestMessage] = {
    import spray.json._
    val dataBytes = rawlsCalls.toJson.prettyPrint.getBytes(StandardCharsets.UTF_8)
    getWorkspaceId(workspaceNamespace, workspaceName, userInfo) map { workspaceId =>
      val bucketToWrite = GcsBucketName(FireCloudConfig.Cwds.bucket)
      val fileToWrite = GcsObjectName(s"to-cwds/${workspaceId}/${java.util.UUID.randomUUID()}.json")
      val gcsPath = writeDataToGcs(bucketToWrite, fileToWrite, dataBytes)
      val importRequest = getRawlsJsonImportRequest(gcsPath, isUpsert)
      importToCWDS(workspaceNamespace, workspaceName, workspaceId, userInfo, importRequest)
    }
  }

  private def writeDataToGcs(bucket: GcsBucketName, objectName: GcsObjectName, data: Array[Byte]): String = {
    val insertedObject = googleServicesDAO.writeObjectAsRawlsSA(bucket, objectName, data)
    s"gs://${insertedObject.bucketName.value}/${insertedObject.objectName.value}"
  }

  private def getRawlsJsonImportRequest(gcsPath: String, isUpsert: Boolean): AsyncImportRequest = {
    AsyncImportRequest(gcsPath, FILETYPE_RAWLS, Some(ImportOptions(None, Some(isUpsert))))
  }

  private def getWorkspaceId(workspaceNamespace: String, workspaceName: String, userInfo: UserInfo): Future[String] = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)(userInfo).map(_.workspace.workspaceId)
  }

  private def importToCWDS(workspaceNamespace: String, workspaceName: String, workspaceId: String, userInfo: UserInfo, importRequest: AsyncImportRequest
                          ): PerRequestMessage = {
    // create the job in cWDS
    val cwdsJob = cwdsDAO.importV1(workspaceId, importRequest)(userInfo)
    // massage the cWDS job into the response format Orch requires

    RequestComplete(Accepted, AsyncImportResponse(url = importRequest.url,
      jobId = cwdsJob.getJobId.toString,
      workspace = WorkspaceName(workspaceNamespace, workspaceName)))
  }

  private def handleBatchRawlsResponse(entityType: String, response: Future[HttpResponse]): Future[PerRequestMessage] = {
    response map { response =>
      response.status match {
        case NoContent =>
          logger.debug("OK response")
          RequestComplete(OK, entityType)
        case _ =>
          // Bubble up all other unmarshallable responses
          logger.warn("Unanticipated response: " + response.status.defaultMessage)
          RequestComplete(response)
      }
    } recover {
      case e: Throwable => RequestCompleteWithErrorReport(InternalServerError,  "Service API call failed", e)
    }
  }

  private def importEntitiesFromTSVLoadFile(workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, tsvType: TsvType, entityType: String, userInfo: UserInfo, isAsync: Boolean, deleteEmptyValues: Boolean): Future[PerRequestMessage] = {
    tsvType match {
      case TsvTypes.MEMBERSHIP => importMembershipTSV(workspaceNamespace, workspaceName, tsv, entityType, userInfo, isAsync)
      case TsvTypes.ENTITY => importEntityTSV(workspaceNamespace, workspaceName, tsv, entityType, userInfo, isAsync, deleteEmptyValues)
      case TsvTypes.UPDATE => importUpdateTSV(workspaceNamespace, workspaceName, tsv, entityType, userInfo, isAsync, deleteEmptyValues)
      case _ => Future(RequestCompleteWithErrorReport(BadRequest, "Invalid TSV type.")) //We should never get to this case
    }
  }

  /**
   * Determines the TSV type from the first column header and routes it to the correct import function. */
  def importEntitiesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String, userInfo: UserInfo, isAsync: Boolean = false, deleteEmptyValues: Boolean = false): Future[PerRequestMessage] = {

    def stripEntityType(entityTypeString: String): String = {
      val entityType = entityTypeString.stripSuffix("_id")
      if (entityType == entityTypeString)
        throw new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.BadRequest, "Invalid first column header, entity type should end in _id"))
      entityType
    }

    withTSVFile(tsvString) { tsv =>
      val (tsvType, entityType) = tsv.firstColumnHeader.split(":") match {
        case Array(entityTypeString) => (TsvTypes.ENTITY, stripEntityType(entityTypeString))
        case Array(tsvTypeString, entityTypeString) =>
          val tsvType = Try(TsvTypes.withName(tsvTypeString)) match {
            case Success(t) => t
            case Failure(err) => throw new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.BadRequest, err.toString))
          }
          (tsvType, stripEntityType(entityTypeString))
        case _ => throw new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.BadRequest, "Invalid first column header, should look like tsvType:entity_type_id"))
      }

      val strippedTsv = if (modelSchema.supportsBackwardsCompatibleIds()) {
          backwardsCompatStripIdSuffixes(tsv, entityType, modelSchema)
        } else {
          tsv
        }
      importEntitiesFromTSVLoadFile(workspaceNamespace, workspaceName, strippedTsv, tsvType, entityType, userInfo, isAsync, deleteEmptyValues)
    }
  }

  def importJob(workspaceNamespace: String, workspaceName: String, importRequest: AsyncImportRequest, userInfo: UserInfo): Future[PerRequestMessage] = {
    // validate that filetype exists in the importRequest
    if (importRequest.filetype.isEmpty)
      throw new FireCloudExceptionWithErrorReport(ErrorReport(BadRequest, "filetype must be specified"))

    getWorkspaceId(workspaceNamespace, workspaceName, userInfo) map { workspaceId =>
      importToCWDS(workspaceNamespace, workspaceName, workspaceId, userInfo, importRequest)
    }
  }

  def listJobs(workspaceNamespace: String, workspaceName: String, runningOnly: Boolean, userInfo: UserInfo): Future[List[CwdsListResponse]] = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)(userInfo) map { workspace =>
      cwdsDAO.listJobsV1(workspace.workspace.workspaceId, runningOnly)(userInfo)
    }
  }

  def getJob(workspaceNamespace: String, workspaceName: String, jobId: String, userInfo: UserInfo): Future[CwdsListResponse] = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)(userInfo) map { workspace =>
      val cwdsResponse = cwdsDAO.getJobV1(workspace.workspace.workspaceId, jobId)(userInfo)
      logger.info(s"Found job $jobId in cWDS")
      cwdsResponse
    }
  }

  def getEntitiesWithType(workspaceNamespace: String, workspaceName: String, userInfo: UserInfo): Future[PerRequestMessage] = {
    rawlsDAO.getEntityTypes(workspaceNamespace, workspaceName)(userInfo).flatMap { entityTypeResponse =>
      val entityTypes = entityTypeResponse.keys.toList

      val entitiesForTypes = Future.traverse(entityTypes) { entityType =>
        rawlsDAO.fetchAllEntitiesOfType(workspaceNamespace, workspaceName, entityType)(userInfo)
      }

      entitiesForTypes.map { result =>
        RequestComplete(OK, result.flatten)
      }
    }
  }

}
