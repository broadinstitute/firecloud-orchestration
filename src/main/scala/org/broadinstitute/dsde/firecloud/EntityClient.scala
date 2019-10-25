package org.broadinstitute.dsde.firecloud

import java.io.{File, FileNotFoundException, FileOutputStream, InputStream}
import java.net.{HttpURLConnection, URL}
import java.text.SimpleDateFormat
import java.util.zip.{ZipEntry, ZipFile}

import akka.actor.{Actor, Props}
import akka.pattern.pipe
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.EntityClient._
import org.broadinstitute.dsde.firecloud.FireCloudConfig.Rawls
import org.broadinstitute.dsde.firecloud.dataaccess.GoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelSchema
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectiveUtils, FireCloudRequestBuilding, TSVFileSupport, TsvTypes}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TsvTypes.TsvType
import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile
import spray.client.pipelining._
import spray.http.HttpEncodings._
import spray.http.HttpHeaders.`Accept-Encoding`
import spray.http.{HttpRequest, HttpResponse}
import spray.http.StatusCodes._
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.httpx.encoding.Gzip
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.routing.RequestContext

import scala.collection.JavaConverters._
import scala.collection.immutable.Set
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.io.Source
import sys.process._
import scala.util.{Failure, Success, Try}

object EntityClient {

  lazy val arrowRoot: String = FireCloudConfig.Arrow.baseUrl
  lazy val arrowAppName: String = FireCloudConfig.Arrow.appName
  lazy val avroToRawlsURL: String = s"$arrowRoot/$arrowAppName"

  case class ImportEntitiesFromTSV(workspaceNamespace: String,
                                   workspaceName: String,
                                   tsvString: String)

  case class ImportBagit(workspaceNamespace: String, workspaceName: String, bagitRq: BagitImportRequest)

  case class ImportPFB(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest, userInfo: UserInfo)
  case class PFBImportStatus(workspaceNamespace: String, workspaceName: String, jobId: String, userInfo: UserInfo)

  def props(entityClientConstructor: (RequestContext, ModelSchema) => EntityClient, requestContext: RequestContext,
            modelSchema: ModelSchema)(implicit executionContext: ExecutionContext): Props = {
    Props(entityClientConstructor(requestContext, modelSchema))
  }

  def constructor(app: Application)(requestContext: RequestContext,
                                    modelSchema: ModelSchema)(implicit executionContext: ExecutionContext) =
    new EntityClient(requestContext, modelSchema, app.googleServicesDAO)

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
    Stream.continually(zis.read(buffer)).takeWhile(_ != -1).foreach(fout.write(buffer, 0, _))
  }

}

class EntityClient (requestContext: RequestContext, modelSchema: ModelSchema, googleServicesDAO: GoogleServicesDAO)(implicit protected val executionContext: ExecutionContext)
  extends Actor with FireCloudRequestBuilding with TSVFileSupport with LazyLogging {

  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {
    case ImportEntitiesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) =>
      val pipeline = authHeaders(requestContext) ~> sendReceive
      importEntitiesFromTSV(pipeline, workspaceNamespace, workspaceName, tsvString) pipeTo sender
    case ImportBagit(workspaceNamespace: String, workspaceName: String, bagitRq: BagitImportRequest) =>
      val pipeline = authHeaders(requestContext) ~> sendReceive
      importBagit(pipeline, workspaceNamespace, workspaceName, bagitRq) pipeTo sender
    case ImportPFB(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest, userInfo: UserInfo) =>
      importPFB(workspaceNamespace, workspaceName, pfbRequest, userInfo) pipeTo sender
    case PFBImportStatus(workspaceNamespace: String, workspaceName: String, jobId: String, userInfo: UserInfo) =>
      pfbImportStatus(workspaceNamespace, workspaceName, jobId, userInfo) pipeTo sender
  }


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

  def batchCallToRawls(
    pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    workspaceNamespace: String, workspaceName: String, entityType: String, calls: Seq[EntityUpdateDefinition], endpoint: String ): Future[PerRequestMessage] = {
    logger.debug("TSV upload request received")

    val responseFuture: Future[HttpResponse] = pipeline {
      Post(FireCloudDirectiveUtils.encodeUri(Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)+"/"+endpoint),
            HttpEntity(MediaTypes.`application/json`,calls.toJson.toString))
    }

    responseFuture map { response =>
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

  /**
   * Imports collection members into a collection type entity. */
  private def importMembershipTSV(
    pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, entityType: String ): Future[PerRequestMessage] = {
    withMemberCollectionType(entityType, modelSchema) { memberTypeOpt =>
      validateMembershipTSV(tsv, memberTypeOpt) {
        withPlural(memberTypeOpt.get) { memberPlural =>
          val rawlsCalls = tsv.tsvData groupBy(_.head) map { case (entityName, rows) =>
            val ops = rows map { row =>
              //row(1) is the entity to add as a member of the entity in row.head
              val attrRef = AttributeEntityReference(memberTypeOpt.get,row(1))
              Map(addListMemberOperation,"attributeListName"->AttributeString(memberPlural),"newMember"->attrRef)
            }
            EntityUpdateDefinition(entityName,entityType,ops)
          }
          batchCallToRawls(pipeline, workspaceNamespace, workspaceName, entityType, rawlsCalls.toSeq, "batchUpsert")
        }
      }
    }
  }

  /**
   * Creates or updates entities from an entity TSV. Required attributes must exist in column headers. */
  private def importEntityTSV(
    pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, entityType: String ): Future[PerRequestMessage] = {
    //we're setting attributes on a bunch of entities
    checkFirstColumnDistinct(tsv) {
      withMemberCollectionType(entityType, modelSchema) { memberTypeOpt =>
        checkNoCollectionMemberAttribute(tsv, memberTypeOpt) {
          withRequiredAttributes(entityType, tsv.headers) { requiredAttributes =>
            val colInfo = colNamesToAttributeNames(tsv.headers, requiredAttributes)
            val rawlsCalls = tsv.tsvData.map(row => setAttributesOnEntity(entityType, memberTypeOpt, row, colInfo, modelSchema))
            batchCallToRawls(pipeline, workspaceNamespace, workspaceName, entityType, rawlsCalls, "batchUpsert")
          }
        }
      }
    }
  }

  /**
   * Updates existing entities from TSV. All entities must already exist. */
  private def importUpdateTSV(
    pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, entityType: String ): Future[PerRequestMessage] = {
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
              val rawlsCalls = tsv.tsvData.map(row => setAttributesOnEntity(entityType, memberTypeOpt, row, colInfo, modelSchema))
              batchCallToRawls(pipeline, workspaceNamespace, workspaceName, entityType, rawlsCalls, "batchUpdate")
          }
        }
      }
    }
  }

  private def importEntitiesFromTSVLoadFile(pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
                        workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, tsvType: TsvType, entityType: String): Future[PerRequestMessage] = {
    tsvType match {
      case TsvTypes.MEMBERSHIP => importMembershipTSV(pipeline, workspaceNamespace, workspaceName, tsv, entityType)
      case TsvTypes.ENTITY => importEntityTSV(pipeline, workspaceNamespace, workspaceName, tsv, entityType)
      case TsvTypes.UPDATE => importUpdateTSV(pipeline, workspaceNamespace, workspaceName, tsv, entityType)
      case _ => Future(RequestCompleteWithErrorReport(BadRequest, "Invalid TSV type.")) //We should never get to this case
    }
  }

  /**
   * Determines the TSV type from the first column header and routes it to the correct import function. */
  def importEntitiesFromTSV(pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    workspaceNamespace: String, workspaceName: String, tsvString: String): Future[PerRequestMessage] = {

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

      val strippedTsv = if (modelSchema.supportsBackwardsCompatibleIds) {
          backwardsCompatStripIdSuffixes(tsv, entityType, modelSchema)
        } else {
          tsv
        }
      importEntitiesFromTSVLoadFile(pipeline, workspaceNamespace, workspaceName, strippedTsv, tsvType, entityType)
    }
  }

  def importBagit(pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]], workspaceNamespace: String, workspaceName: String, bagitRq: BagitImportRequest): Future[PerRequestMessage] = {
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

        try {
          val conn = bagitURL.openConnection()
          val length = conn.getContentLength
          conn.asInstanceOf[HttpURLConnection].disconnect()

          if (length > Rawls.entityBagitMaximumSize) {
            Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, s"BDBag size is too large."))
          } else {
            //this magic creates a process that downloads a URL to a file (which is #>), and then runs the process (which is !!)
            bagitURL #> bagItFile !!

            //make two big strings containing the participants and samples TSVs
            //if i could turn back time this would use streams to save memory, but hopefully this will all go away when entity service comes along
            unzipTSVs(bagitRq.bagitURL, new ZipFile(bagItFile.getAbsolutePath)) { (participantsStr, samplesStr) =>
              (participantsStr, samplesStr) match {
                case (None, None) =>
                  Future.successful(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "You must have either (or both) participants.tsv and samples.tsv in the zip file"))
                case _ =>
                  for {
                    // This should vomit back errors from rawls.
                    participantResult <- participantsStr.map(ps => importEntitiesFromTSV(pipeline, workspaceNamespace, workspaceName, ps)).getOrElse(Future.successful(RequestComplete(OK)))
                    sampleResult <- samplesStr.map(ss => importEntitiesFromTSV(pipeline, workspaceNamespace, workspaceName, ss)).getOrElse(Future.successful(RequestComplete(OK)))
                  } yield {
                    participantResult match {
                      case RequestComplete((OK, _)) => sampleResult
                      case _ => participantResult
                    }
                  }
              }
            }
          }
        } catch {
          case e: FileNotFoundException => Future.successful(RequestCompleteWithErrorReport(StatusCodes.NotFound, s"BDBag ${bagitRq.bagitURL} was not found."))
          case e: Exception => throw e
        } finally {
          bagItFile.delete()
        }
      }
    }
  }

  def importPFB(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest, userInfo: UserInfo): Future[PerRequestMessage] = {

    // initial validation of the presigned url provided as an argument
    def validatePfbUrl(pfbRequest: PfbImportRequest)(op: URL => Future[PerRequestMessage]) = {
      val pfbUrl = try {
        new URL(pfbRequest.url)
      } catch {
        case e: Exception => throw new FireCloudExceptionWithErrorReport(ErrorReport(BadRequest, s"Invalid URL: ${pfbRequest.url}", e))
      }
      val acceptableProtocols = Seq("https") //for when we inevitably change our mind and need to support others

      if (!acceptableProtocols.contains(pfbUrl.getProtocol)) {
        throw new FireCloudExceptionWithErrorReport(ErrorReport(BadRequest, "Invalid URL protocol: must be https only"))
      }

      // TODO: add real (semantic) validation for origins, to ensure caller isn't supplying malice
      op(pfbUrl)
    }

    // probe Rawls with an empty upsert to this workspace. This checks that the workspace exists and that the user has
    // permissions to perform an upsert.
    def validateUpsertPermissions()(op: Boolean => PerRequestMessage) = {
      val pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]] = authHeaders(requestContext) ~> sendReceive

      val probeUpsert = pipeline {
        Post(FireCloudDirectiveUtils.encodeUri(Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName) + "/batchUpsert"), List.empty[String])
      }

      probeUpsert.map {
        case resp if resp.status == NoContent => op(true)
        case resp => RequestComplete(resp) // bubble up errors
      }
    }

    // validate presigned URL
    validatePfbUrl(pfbRequest) { pfbUrl =>
      // empty-array batchUpsert to Rawls to verify workspace existence and permissions
      validateUpsertPermissions() { _ =>
        // generate job UUID
        val jobId = java.util.UUID.randomUUID()

        // the payload to Arrow needs to include the PFB url, job id, workspace info, and user info
        val user = WorkbenchUserInfo(userInfo.id, userInfo.userEmail)
        val arrowPayload = pfbRequest.copy(jobId = Option(jobId.toString),
          workspace = Option(WorkspaceName(workspaceNamespace, workspaceName)),
          user = Option(user))

        // fire-and-forget call Arrow with UUID, workspace info, and presigned URL
        val idToken = googleServicesDAO.getAdminIdentityToken
        val gzipPipeline = addHeader (`Accept-Encoding`(gzip)) ~> addCredentials(OAuth2BearerToken(idToken)) ~> sendReceive ~> decode(Gzip)
        gzipPipeline { Post(avroToRawlsURL, arrowPayload) }

        // the response payload to the end user omits the userid/email
        val responsePayload = arrowPayload.copy(user = None)

        // return Accepted with UUID
        RequestComplete(Accepted, responsePayload)
      }
    }
  }

  def pfbImportStatus(workspaceNamespace: String, workspaceName: String, jobId: String, userInfo: UserInfo): Future[PerRequestMessage] = {

    val bucketName = FireCloudConfig.Arrow.bucketName

    def readStatusFile(filename: String, default: String): String = {
      Try(googleServicesDAO.getObjectContentsAsRawlsSA(bucketName, s"$jobId/$filename")) match {
        case Success(msg) =>
          if (msg.trim.nonEmpty) msg else default
        case Failure(ex) =>
          logger.warn(s"error reading GCS object gs://$bucketName/$jobId/$filename", ex)
          default
      }
    }

    // list all files in the jobId's subdirectory and strip jobId prefix so we have just the filenames
    Try(googleServicesDAO.listObjectsAsRawlsSA(bucketName, jobId + "/").map(_.replace(jobId + "/",""))) match {

      case Failure(ex) =>
        logger.warn(s"Error listing GCS objects for prefix gs://$bucketName/$jobId", ex)
        // do not expose the bucket name in the error report we send to users
        Future.successful(RequestCompleteWithErrorReport(InternalServerError, "Error listing bucket"))

      case Success(files) =>
        // what files do we have?
        val (statusCode, statusString, message) = files.toSet match {

          // no files yet, or subdirectory doesn't exist
          case nothing if nothing.isEmpty =>
            (NotFound, "NOT FOUND", "jobId not found or job not yet started")

          // somebody wrote an error.json: it's an error.
          case error if error.contains("error.json") =>
            // try to read the error contents
            val errorMessage = readStatusFile("error.json", "Error!")
            (OK, "ERROR", errorMessage)

          // we finished with a success.json: it's a success. Yay!
          case success if success.contains("success.json") =>
            // try to read the success contents
            val successMessage = readStatusFile("success.json", "Success! Import completed.")
            (OK, "SUCCESS", successMessage)

          // we have a start marker, but do not yet have a success or error marker. It's in progress.
          case started if started.contains("running.json") =>
            val runningMessage = readStatusFile("running.json", "import in progress")
            (OK, "RUNNING", runningMessage)

          // we've got either/both of the upsert and the metadata, but no other markers - it's in progress.
          case alsostarted if alsostarted == Set("upsert.json") || alsostarted == Set("metadata.json") || alsostarted == Set("upsert.json", "metadata.json") =>
            (OK, "RUNNING", "import in progress")

          // the files don't make sense; give up.
          case _ =>
            logger.warn(s"could not determine importPFB status for $jobId: $files")
            (InternalServerError, "UNKNOWN", "could not determine status")
        }

        val responsePayload = JsObject(
          "status" -> JsString(statusString),
          "message" -> JsString(message),
          "jobId" -> JsString(jobId)
        )

        Future.successful(RequestComplete(statusCode, responsePayload))
    }
  }

}
