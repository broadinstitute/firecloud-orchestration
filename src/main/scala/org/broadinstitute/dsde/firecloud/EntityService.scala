package org.broadinstitute.dsde.firecloud

import java.io.{File, FileNotFoundException, FileOutputStream, InputStream}
import java.net.{HttpURLConnection, URL}
import java.text.SimpleDateFormat
import java.util.zip.{ZipEntry, ZipException, ZipFile}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.EntityService._
import org.broadinstitute.dsde.firecloud.FireCloudConfig.Rawls
import org.broadinstitute.dsde.firecloud.dataaccess.ImportServiceFiletypes.FILETYPE_RAWLS
import org.broadinstitute.dsde.firecloud.dataaccess.{CwdsDAO, GoogleServicesDAO, ImportServiceDAO, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, _}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TsvTypes.TsvType
import org.broadinstitute.dsde.firecloud.service.{TSVFileSupport, TsvTypes}
import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName}
import spray.json.DefaultJsonProtocol._

import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object EntityService {

  def constructor(app: Application)(modelSchema: ModelSchema)(implicit executionContext: ExecutionContext) =
    new EntityService(app.rawlsDAO, app.importServiceDAO, app.cwdsDAO, app.googleServicesDAO, modelSchema)

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

  //returns (contents of participants.tsv, contents of samples.tsv)
  def unzipTSVs(bagName: String, zipFile: ZipFile)(op: (Option[String], Option[String]) => Future[PerRequestMessage]): Future[PerRequestMessage] = {
    val zipEntries = zipFile.entries.asScala

    val rand = java.util.UUID.randomUUID.toString.take(8)
    val participantsTmp = File.createTempFile(s"$rand-participants", ".tsv")
    val samplesTmp = File.createTempFile(s"$rand-samples", ".tsv")

    val unzippedFiles = zipEntries.foldLeft((None: Option[String], None: Option[String])){ (acc: (Option[String], Option[String]), ent: ZipEntry) =>
      if(!ent.isDirectory && (ent.getName.contains("/participants.tsv") || ent.getName.equals("participants.tsv"))) {
        acc._1 match {
          case Some(_) => throw new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.BadRequest, s"More than one participants.tsv file found in bagit $bagName"))
          case None =>
            unzipSingleFile(zipFile.getInputStream(ent), participantsTmp)
            (Some(participantsTmp.getPath), acc._2)
        }
      } else if(!ent.isDirectory && (ent.getName.contains("/samples.tsv") || ent.getName.equals("samples.tsv"))) {
        acc._2 match {
          case Some(_) => throw new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.BadRequest, s"More than one samples.tsv file found in bagit $bagName"))
          case None =>
              unzipSingleFile (zipFile.getInputStream (ent), samplesTmp)
              (acc._1, Some (samplesTmp.getPath) )
        }
      } else {
        acc
      }
    }

    try {
      op(unzippedFiles._1.map(f => Source.fromFile(f).mkString), unzippedFiles._2.map(f => Source.fromFile(f).mkString))
    } catch {
      case e: Exception => throw e
    } finally {
      participantsTmp.delete
      samplesTmp.delete
    }
  }

  private def unzipSingleFile(zis: InputStream, fileTarget: File): Unit = {
    val fout = new FileOutputStream(fileTarget)
    val buffer = new Array[Byte](1024)
    LazyList.continually(zis.read(buffer)).takeWhile(_ != -1).foreach(fout.write(buffer, 0, _))
  }

}

class EntityService(rawlsDAO: RawlsDAO, importServiceDAO: ImportServiceDAO, cwdsDAO: CwdsDAO, googleServicesDAO: GoogleServicesDAO, modelSchema: ModelSchema)(implicit val executionContext: ExecutionContext)
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

    // generate unique name for the file-to-upload
    val fileToWrite = GcsObjectName(s"incoming/${java.util.UUID.randomUUID()}.json")
    val bucketToWrite = GcsBucketName(FireCloudConfig.ImportService.bucket)

    // write rawlsCalls to import service's bucket
    val dataBytes = rawlsCalls.toJson.prettyPrint.getBytes(StandardCharsets.UTF_8)
    val insertedObject = googleServicesDAO.writeObjectAsRawlsSA(bucketToWrite, fileToWrite, dataBytes)
    val gcsPath = s"gs://${insertedObject.bucketName.value}/${insertedObject.objectName.value}"

    val importRequest = AsyncImportRequest(gcsPath, FILETYPE_RAWLS)
    importServiceDAO.importJob(workspaceNamespace, workspaceName, importRequest, isUpsert)(userInfo)
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

  def importBagit(workspaceNamespace: String, workspaceName: String, bagitRq: BagitImportRequest, userInfo: UserInfo): Future[PerRequestMessage] = {
    if(bagitRq.format != "TSV") {
      Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "Invalid format; for now, you must place the string \"TSV\" here"))
    } else {

      //Java URL handles http, https, ftp, file, and jar protocols.
      //We're only OK with https to avoid MITM attacks.
      val bagitURL = new URL(bagitRq.bagitURL.replace(" ", "%20"))
      val acceptableProtocols = Seq("https") //for when we inevitably change our mind and need to support others
      if (!acceptableProtocols.contains(bagitURL.getProtocol)) {
        Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "Invalid bagitURL protocol: must be https only"))
      } else {

        val rand = java.util.UUID.randomUUID.toString.take(8)
        val bagItFile = File.createTempFile(s"$rand-samples", ".tsv")
        var bytesDownloaded = new AtomicLong(-1) // careful, this is a var

        try {
          val conn = bagitURL.openConnection()
          val length = conn.getContentLength
          conn.asInstanceOf[HttpURLConnection].disconnect()

          if (length == 0) {
            Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, s"BDBag has content-length 0"))
          } else if (length > Rawls.entityBagitMaximumSize) {
            Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, s"BDBag size is too large."))
          } else {
            // download the file
            val readFromBagit = Channels.newChannel(bagitURL.openStream())
            val writeToTemp = new FileOutputStream(bagItFile)
            try {
              bytesDownloaded.set(writeToTemp.getChannel.transferFrom(readFromBagit, 0, length))
            } finally {
              readFromBagit.close()
              writeToTemp.close()
            }

            val zipFile = new ZipFile(bagItFile.getAbsolutePath)
            if (!zipFile.entries().hasMoreElements) {
              Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest, s"BDBag has no entries."))
            } else {
              //make two big strings containing the participants and samples TSVs
              //if i could turn back time this would use streams to save memory, but hopefully this will all go away when entity service comes along
              unzipTSVs(bagitRq.bagitURL, zipFile) { (participantsStr, samplesStr) =>
                (participantsStr, samplesStr) match {
                  case (None, None) =>
                    Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "You must have either (or both) participants.tsv and samples.tsv in the zip file"))
                  case _ =>
                    for {
                      // This should vomit back errors from rawls.
                      participantResult <- participantsStr.map(ps => importEntitiesFromTSV(workspaceNamespace, workspaceName, ps, userInfo)).getOrElse(Future.successful(RequestComplete(OK)))
                      sampleResult <- samplesStr.map(ss => importEntitiesFromTSV(workspaceNamespace, workspaceName, ss, userInfo)).getOrElse(Future.successful(RequestComplete(OK)))
                    } yield {
                      participantResult match {
                        case RequestComplete((OK, _)) => sampleResult
                        case _ => participantResult
                      }
                    }
                }
              }
            }
          }
        } catch {
          case _:FileNotFoundException =>
            Future.successful(RequestCompleteWithErrorReport(StatusCodes.NotFound, s"BDBag ${bagitRq.bagitURL} was not found."))
          case ze:ZipException =>
            logger.info(s"ZipException: ${ze.getMessage} - ${bagItFile.getAbsolutePath} has length ${bagItFile.length}. " +
              s"We originally downloaded $bytesDownloaded bytes.")
            Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, s"Problem with BDBag: ${ze.getMessage}"))
          case e: Exception =>
            throw e
        } finally {
          bagItFile.delete()
        }
      }
    }
  }

  def importJob(workspaceNamespace: String, workspaceName: String, importRequest: AsyncImportRequest, userInfo: UserInfo): Future[PerRequestMessage] = {
    // validate that filetype exists in the importRequest
    if (importRequest.filetype.isEmpty)
      throw new FireCloudExceptionWithErrorReport(ErrorReport(BadRequest, "filetype must be specified"))

    // if cwds.enabled, for cwds filetypes send the request to cWDS instead of import service
    if (cwdsDAO.isEnabled && cwdsDAO.getSupportedFormats.contains(importRequest.filetype.toLowerCase)) {
      // translate the workspace namespace/name into an id
      rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)(userInfo) map { workspace =>
        // create the job in cWDS
        val cwdsJob = cwdsDAO.importV1(workspace.workspace.workspaceId, importRequest)(userInfo)
        // massage the cWDS job into the response format Orch requires
        val asyncImportResponse = AsyncImportResponse(url = importRequest.url,
          jobId = cwdsJob.getJobId.toString,
          workspace = WorkspaceName(workspaceNamespace, workspaceName))
        RequestComplete(Accepted, asyncImportResponse)
      }
    } else {
      importServiceDAO.importJob(workspaceNamespace, workspaceName, importRequest, isUpsert = true)(userInfo)
    }


  }

  def listJobs(workspaceNamespace: String, workspaceName: String, runningOnly: Boolean, userInfo: UserInfo): Future[List[ImportServiceListResponse]] = {

    for {
      // get jobs from Import Service
      importServiceJobs <- importServiceDAO.listJobs(workspaceNamespace, workspaceName, runningOnly)(userInfo)
      // get jobs from cWDS
      cwdsJobs <- if (cwdsDAO.isEnabled) {
        rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)(userInfo) map { workspace =>
          cwdsDAO.listJobsV1(workspace.workspace.workspaceId, runningOnly)(userInfo)
        }
      } else {
        Future.successful(List.empty)
      }
    } yield {
      // merge Import Service and cWDS results
      importServiceJobs.concat(cwdsJobs)
    }
  }

  def getJob(workspaceNamespace: String, workspaceName: String, jobId: String, userInfo: UserInfo): Future[ImportServiceListResponse] = {
    // there is no way to tell just from a jobId if the job exists in cWDS or Import Service. So, we have to try both.

    // if cWDS is enabled, query cWDS for job
    val cwdsFuture: Future[ImportServiceListResponse] = if (cwdsDAO.isEnabled) {
      rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)(userInfo) map { workspace =>
        val cwdsResponse = cwdsDAO.getJobV1(workspace.workspace.workspaceId, jobId)(userInfo)
        logger.info(s"Found job $jobId in cWDS")
        cwdsResponse
      }
    } else {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Import $jobId not found")))
    }

    val importServiceFuture: () => Future[ImportServiceListResponse] = () => importServiceDAO.getJob(workspaceNamespace, workspaceName, jobId)(userInfo) map { importServiceResponse =>
      logger.info(s"Found job $jobId in Import Service")
      importServiceResponse
    } recover { importServiceError =>
      logger.info(s"Job $jobId not found in either cWDS or Import Service: " + importServiceError.getClass.getName)
      importServiceError match {
        case fex: FireCloudExceptionWithErrorReport => throw fex
        case   t => throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, t))
      }
    }

    // if cWDS found the job, return it; else, try Import Service
    cwdsFuture recoverWith {
      case _ => importServiceFuture.apply()
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
