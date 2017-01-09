package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe
import org.broadinstitute.dsde.firecloud.EntityClient._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{TsvType, FireCloudRequestBuilding, TSVFileSupport}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.{TSVLoadFile, TSVParser}
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object EntityClient {

  case class ImportEntitiesFromTSV(workspaceNamespace: String,
                                   workspaceName: String,
                                   tsvString: String)

  def props(requestContext: RequestContext): Props = Props(new EntityClient(requestContext))

  def colNamesToAttributeNames(entityType: String, headers: Seq[String], requiredAttributes: Map[String,String]) = {
    headers.tail map { colName => (colName, requiredAttributes.get(colName))}
  }

}

class EntityClient (requestContext: RequestContext) extends Actor with FireCloudRequestBuilding with TSVFileSupport {

  import system.dispatcher

  implicit val system = context.system
  val log = Logging(system, getClass)
  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {
    case ImportEntitiesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) =>
      val pipeline = authHeaders(requestContext) ~> sendReceive
      importEntitiesFromTSV(pipeline, workspaceNamespace, workspaceName, tsvString) pipeTo context.parent
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
    log.info("TSV upload request received")

    val responseFuture: Future[HttpResponse] = pipeline {
      Post(FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)+"/"+endpoint,
            HttpEntity(MediaTypes.`application/json`,calls.toJson.toString))
    }

    responseFuture map { response =>
      response.status match {
          case NoContent =>
            log.debug("OK response")
            RequestComplete(OK, calls.head.entityType)
          case _ =>
            // Bubble up all other unmarshallable responses
            log.warning("Unanticipated response: " + response.status.defaultMessage)
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
            val colInfo = colNamesToAttributeNames(entityType, tsv.headers, requiredAttributes)
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
              val colInfo = colNamesToAttributeNames(entityType, tsv.headers, requiredAttributes)
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
            tsvType match {
              case TsvType.MEMBERSHIP => importMembershipTSV(pipeline, workspaceNamespace, workspaceName, tsv, entityType)
              case TsvType.ENTITY => importEntityTSV(pipeline, workspaceNamespace, workspaceName, tsv, entityType)
              case TsvType.UPDATE => importUpdateTSV(pipeline, workspaceNamespace, workspaceName, tsv, entityType)
              case _ =>
                Future(RequestCompleteWithErrorReport(BadRequest, "Invalid TSV type, supported types are: membership, entity, update"))
            }
          }
        case _ =>
          Future(RequestCompleteWithErrorReport(BadRequest, "Invalid first column header, should look like tsvType:entity_type_id"))
      }
    }
  }

}
