package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat

import akka.actor.{Actor, Props}
import akka.pattern.pipe
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.EntityClient._
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{FireCloudRequestBuilding, TSVFileSupport, TsvTypes}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object EntityClient {

  case class ImportEntitiesFromTSV(workspaceNamespace: String,
                                   workspaceName: String,
                                   tsvString: String)

  def props(requestContext: RequestContext)(implicit executionContext: ExecutionContext): Props = Props(new EntityClient(requestContext))

  def colNamesToAttributeNames(headers: Seq[String], requiredAttributes: Map[String, String]): Seq[(String, Option[String])] = {
    headers.tail map { colName => (colName, requiredAttributes.get(colName))}
  }

  def backwardsCompatStripIdSuffixes(tsvLoadFile: TSVLoadFile, entityType: String): TSVLoadFile = {
    ModelSchema.getTypeSchema(entityType) match {
      case Failure(regrets) => tsvLoadFile
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

class EntityClient (requestContext: RequestContext)(implicit protected val executionContext: ExecutionContext)
  extends Actor with FireCloudRequestBuilding with TSVFileSupport with LazyLogging {

  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {
    case ImportEntitiesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) =>
      val pipeline = authHeaders(requestContext) ~> sendReceive
      importEntitiesFromTSV(pipeline, workspaceNamespace, workspaceName, tsvString) pipeTo sender
  }


  /**
   * Returns the plural form of the entity type.
   * Bails with a 400 Bad Request if the entity type is unknown to the schema. */
  private def withPlural(entityType: String)(op: (String => Future[PerRequestMessage])): Future[PerRequestMessage] = {
    ModelSchema.getPlural(entityType) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
      case Success(plural) => op(plural)
    }
  }


  /**
   * Verifies that the provided list of headers includes all attributes required by the schema for this entity type.
   * Bails with a 400 Bad Request if the entity type is unknown or if some attributes are missing.
   * Returns the list of required attributes if all is well. */
  private def withRequiredAttributes(entityType: String, headers: Seq[String])(op: (Map[String, String] => Future[PerRequestMessage])):Future[PerRequestMessage] = {
    ModelSchema.getRequiredAttributes(entityType) match {
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
    workspaceNamespace: String, workspaceName: String, calls: Seq[EntityUpdateDefinition], endpoint: String ): Future[PerRequestMessage] = {
    logger.debug("TSV upload request received")

    val responseFuture: Future[HttpResponse] = pipeline {
      Post(FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)+"/"+endpoint,
            HttpEntity(MediaTypes.`application/json`,calls.toJson.toString))
    }

    responseFuture map { response =>
      response.status match {
          case NoContent =>
            logger.debug("OK response")
            RequestComplete(OK, calls.head.entityType)
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
    withMemberCollectionType(entityType) { memberTypeOpt =>
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
          batchCallToRawls(pipeline, workspaceNamespace, workspaceName, rawlsCalls.toSeq, "batchUpsert")
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
      withMemberCollectionType(entityType) { memberTypeOpt =>
        checkNoCollectionMemberAttribute(tsv, memberTypeOpt) {
          withRequiredAttributes(entityType, tsv.headers) { requiredAttributes =>
            val colInfo = colNamesToAttributeNames(tsv.headers, requiredAttributes)
            val rawlsCalls = tsv.tsvData.map(row => setAttributesOnEntity(entityType, memberTypeOpt, row, colInfo))
            batchCallToRawls(pipeline, workspaceNamespace, workspaceName, rawlsCalls, "batchUpsert")
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
      withMemberCollectionType(entityType) { memberTypeOpt =>
        checkNoCollectionMemberAttribute(tsv, memberTypeOpt) {
          ModelSchema.getRequiredAttributes(entityType) match {
            //Required attributes aren't required to be headers in update TSVs - they should already have been
            //defined when the entity was created. But we still need the type information if the headers do exist.
            case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
            case Success(requiredAttributes) =>
              val colInfo = colNamesToAttributeNames(tsv.headers, requiredAttributes)
              val rawlsCalls = tsv.tsvData.map(row => setAttributesOnEntity(entityType, memberTypeOpt, row, colInfo))
              batchCallToRawls(pipeline, workspaceNamespace, workspaceName, rawlsCalls, "batchUpdate")
          }
        }
      }
    }
  }

  /**
   * Determines the TSV type from the first column header and routes it to the correct import function. */
  def importEntitiesFromTSV(
    pipeline: WithTransformerConcatenation[HttpRequest, Future[HttpResponse]],
    workspaceNamespace: String, workspaceName: String, tsvString: String): Future[PerRequestMessage] = {
    withTSVFile(tsvString) { tsv =>
      //the first column header of the tsv defines:
      // 1. The type of the tsv import file (membership, entity, or update)
      // 2. The entity type we're trying to import or update, which is magically verified in the withFoo functions below.
      tsv.firstColumnHeader.split(":") match {
        case Array( tsvType, entityHeader ) =>
          val entityType = entityHeader.stripSuffix("_id")
          if( entityType == entityHeader ) {
            Future(RequestCompleteWithErrorReport(BadRequest, "Invalid first column header, entity type should end in _id"))
          } else {
            val strippedTsv = backwardsCompatStripIdSuffixes(tsv, entityType)
            Try(TsvTypes.withName(tsvType)) match {
              case Success(TsvTypes.MEMBERSHIP) => importMembershipTSV (pipeline, workspaceNamespace, workspaceName, strippedTsv, entityType)
              case Success(TsvTypes.ENTITY) => importEntityTSV (pipeline, workspaceNamespace, workspaceName, strippedTsv, entityType)
              case Success(TsvTypes.UPDATE) => importUpdateTSV (pipeline, workspaceNamespace, workspaceName, strippedTsv, entityType)
              case Failure(err) => Future(RequestCompleteWithErrorReport(BadRequest, err.toString))
            }
          }
        case _ =>
          Future(RequestCompleteWithErrorReport(BadRequest, "Invalid first column header, should look like tsvType:entity_type_id"))
      }
    }
  }
}
