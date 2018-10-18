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
import org.broadinstitute.dsde.firecloud.model.ModelSchema
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectiveUtils, FireCloudRequestBuilding, TSVFileSupport, TsvTypes}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TsvTypes.TsvType
import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.routing.RequestContext

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.io.Source
import sys.process._
import scala.util.{Failure, Success, Try}

object EntityClient {

  case class ImportEntitiesFromTSV(workspaceNamespace: String,
                                   workspaceName: String,
                                   tsvString: String)

  case class ImportBagit(workspaceNamespace: String, workspaceName: String, bagitRq: BagitImportRequest)

  def props(requestContext: RequestContext, modelSchema: ModelSchema)(implicit executionContext: ExecutionContext): Props = Props(new EntityClient(requestContext, modelSchema))

  def colNamesToAttributeNames(headers: Seq[String], requiredAttributes: Map[String, String]): Seq[(String, Option[String])] = {
    headers.tail map { colName => (colName, requiredAttributes.get(colName))}
  }

  def backwardsCompatStripIdSuffixes(tsvLoadFile: TSVLoadFile, entityType: String, modelSchema: ModelSchema): TSVLoadFile = {
    modelSchema.getTypeSchema(entityType) match {
      case Failure(regrets) => tsvLoadFile // the failure will be handled during parsing
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
          case Some(x) => throw new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.BadRequest, s"More than one participants.tsv file found in bagit $bagName"))
          case None =>
            unzipSingleFile(zipFile.getInputStream(ent), participantsTmp)
            (Some(participantsTmp.getPath), acc._2)
        }
      } else if(!ent.isDirectory && (ent.getName.contains("/samples.tsv") || ent.getName.equals("samples.tsv"))) {
        acc._2 match {
          case Some(x) => throw new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.BadRequest, s"More than one samples.tsv file found in bagit $bagName"))
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

class EntityClient (requestContext: RequestContext, modelSchema: ModelSchema)(implicit protected val executionContext: ExecutionContext)
  extends Actor with FireCloudRequestBuilding with TSVFileSupport with LazyLogging {

  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {
    case ImportEntitiesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) =>
      val pipeline = authHeaders(requestContext) ~> sendReceive
      importEntitiesFromTSV(pipeline, workspaceNamespace, workspaceName, tsvString) pipeTo sender
    case ImportBagit(workspaceNamespace: String, workspaceName: String, bagitRq: BagitImportRequest) =>
      val pipeline = authHeaders(requestContext) ~> sendReceive
      importBagit(pipeline, workspaceNamespace, workspaceName, bagitRq) pipeTo sender
  }


  /**
   * Returns the plural form of the entity type.
   * Bails with a 400 Bad Request if the entity type is unknown to the schema and we are using firecloud model
   * If using flexible model, just appends an 's' */
  private def withPlural(entityType: String)(op: (String => Future[PerRequestMessage])): Future[PerRequestMessage] = {
    modelSchema.getPlural(entityType) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
      case Success(plural) => op(plural)
    }
  }


  /**
   * Verifies that the provided list of headers includes all attributes required by the schema for this entity type.
   * Bails with a 400 Bad Request if the entity type is unknown or if some attributes are missing.
   * Returns the list of required attributes if all is well. */
  private def withRequiredAttributes(entityType: String, headers: Seq[String])(op: (Map[String, String] => Future[PerRequestMessage])):Future[PerRequestMessage] = {
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
          val rawlsCalls = tsv.tsvData groupBy(_(0)) map { case (entityName, rows) =>
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
        case Array(tsvTypeString, entityTypeString) => {
          val tsvType = Try(TsvTypes.withName(tsvTypeString)) match {
            case Success(t) => t
            case Failure(err) => throw new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.BadRequest, err.toString))
          }
          (tsvType, stripEntityType(entityTypeString))
        }
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
}
