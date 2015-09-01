package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat

import org.broadinstitute.dsde.firecloud.service.FireCloudTransformers

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Try, Failure, Success}

import akka.actor.{Actor, Props}
import akka.event.Logging
import spray.client.pipelining._

import spray.http.StatusCodes._
import spray.http._
import spray.routing.RequestContext
import spray.json._
import spray.json.DefaultJsonProtocol._

import org.broadinstitute.dsde.firecloud.EntityClient._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.{TSVParser, TSVLoadFile}

object EntityClient {
  case class EntityListRequest(workspaceNamespace: String,
                               workspaceName: String,
                               entityType: String)

  case class CreateEntities(workspaceNamespace: String,
                            workspaceName: String,
                            entities: JsValue)

  case class ImportEntitiesFromTSV(workspaceNamespace: String,
                                   workspaceName: String,
                                   tsvString: String)

  def props(requestContext: RequestContext): Props = Props(new EntityClient(requestContext))

}

class EntityClient (requestContext: RequestContext) extends Actor with FireCloudTransformers {

  import system.dispatcher

  implicit val system = context.system
  val log = Logging(system, getClass)
  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {
    case EntityListRequest(workspaceNamespace: String, workspaceName: String, entityType: String) =>
      listEntities(workspaceNamespace, workspaceName, entityType)
    case CreateEntities(workspaceNamespace: String, workspaceName: String, entities: JsValue) =>
      createEntities(workspaceNamespace, workspaceName, entities)
    case ImportEntitiesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String) =>
      importEntitiesFromTSV(workspaceNamespace, workspaceName, tsvString)
  }

  def listEntities(workspaceNamespace: String, workspaceName: String, entityType: String): Unit = {
    log.info("listEntities request received")
    val pipeline: HttpRequest => Future[HttpResponse] =
      authHeaders(requestContext) ~> sendReceive
    val responseFuture: Future[HttpResponse] = pipeline {
      Get(s"${FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)}/$entityType")
    }

    responseFuture onComplete {
      case Success(response) =>
        response.status match {
          case OK =>
            log.debug("OK response")
            requestContext.complete(response)
          case _ =>
            // Bubble up all other unmarshallable responses
            log.warning("Unanticipated response: " + response.status.defaultMessage)
            requestContext.complete(response)
        }
      case Failure(error) =>
        // Failure accessing service
        log.error(error, "Service API call failed")
        requestContext.failWith(
          new RequestProcessingException(StatusCodes.InternalServerError, error.getMessage))
    }
  }

  def createEntities(wsNamespace: String, wsName: String, entities: JsValue) = {
    val entityJson = entities.convertTo[Seq[JsObject]]

    // NOTE: this is very hacky, but we cannot support this quick fix upload well because
    // we are accepting json in the Rawls model case class format.  Since we don't have access
    // to the Rawls case classes, we are dealing with the json directly.  We need the entityType
    // and name in order to show errors.  This will all be replaced in our next sprint.
    def createFromJson(entity: JsObject) = {
      // NOTE: these expect that entityType and entityName exist
      val entityType = entity.fields.get("entityType").getOrElse(new JsString("")).convertTo[String]
      val entityName = entity.fields.get("name").getOrElse(new JsString("")).convertTo[String]
      createEntityOrReportError(wsNamespace, wsName, entity, entityType, entityName)
    }
    val results = entityJson.map(createFromJson)

//    val results = entities.map(entity => createEntityOrReportError(wsNamespace, wsName, entity))
    // have to manually construct an HttpResponse here because we're aggregating the results of multiple calls to Rawls
    // TODO the response code should indicate if any of the individual requests were failures
    val response = HttpResponse(OK, HttpClient.createJsonHttpEntity(results.toJson.compactPrint))
    requestContext.complete(response)
  }

  def createEntityOrReportError(workspaceNamespace: String, workspaceName: String, entityJson: JsValue, entityType: String, entityName: String) = {
    val url = FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)
    val externalRequest = Post(url, HttpClient.createJsonHttpEntity(entityJson.compactPrint))
    val pipeline = authHeaders(requestContext) ~> sendReceive
    // TODO figure out how to get the response in a non-blocking way,
    // TODO given that the requestContext should not complete until *all* are finished?
    Try(Await.result(pipeline(externalRequest), Duration.Inf)) match {
      case Success(response) => response.status match {
        case StatusCodes.Created => EntityCreateResult(entityType, entityName, true, "Entity created successfully")
        case _ => EntityCreateResult(entityType, entityName, false, s"Bad response from workspace service: ${response.message}")
      }
      case Failure(t: Throwable) => EntityCreateResult(entityType, entityName, false,
        s"Error sending request to workspace service: ${t.getMessage}")
    }
  }

  /**
   * Attempts to parse a string into a TSVLoadFile.
   * Bails with a 400 Bad Request if the TSV is invalid. */
  private def withTSVFile(tsvString:String)(op: (TSVLoadFile => Unit)): Unit = {
    Try(TSVParser.parse(tsvString)) match {
      case Failure(regret) => requestContext.complete(HttpResponse(BadRequest, regret.getMessage))
      case Success(tsvFile) => op(tsvFile)
    }
  }

  /**
   * Collection type entities have typed members enforced by the schema. If the provided entity type exists, returns
   * Some( its_member_type ) if it's a collection, or None if it isn't.
   * Bails with a 400 Bad Request if the provided entity type is unknown to the schema. */
  private def withMemberCollectionType(entityType: String)(op: (Option[String] => Unit)): Unit = {
    ModelSchema.getCollectionMemberType(entityType) match {
      case Failure(regret) => requestContext.complete(HttpResponse(BadRequest, regret.getMessage))
      case Success(memberTypeOpt) => op(memberTypeOpt)
    }
  }

  /**
   * Returns the plural form of the entity type.
   * Bails with a 400 Bad Request if the entity type is unknown to the schema. */
  private def withPlural(entityType: String)(op: (String => Unit)): Unit = {
    ModelSchema.getPlural(entityType) match {
      case Failure(regret) => requestContext.complete(HttpResponse(BadRequest, regret.getMessage))
      case Success(plural) => op(plural)
    }
  }

  /**
   * Bail with a 400 Bad Request if the first column of the tsv has duplicate values.
   * Otherwise, carry on. */
  private def checkFirstColumnsDistinct( tsv: TSVLoadFile, tsvTypeName: String )(op: => Unit): Unit = {
    val entitiesToUpdate = tsv.tsvData.map(_.headOption.get)
    val distinctEntities = entitiesToUpdate.distinct
    if ( entitiesToUpdate.size != distinctEntities.size ) {
      requestContext.complete( HttpResponse(BadRequest,
        "Duplicated entities are not allowed in an " + tsvTypeName +
          " TSV: " + entitiesToUpdate.diff(distinctEntities).distinct.mkString(", ")) )
    } else {
      op
    }
  }

  /**
   * Bail with a 400 Bad Request if the tsv is trying to set members on a collection type.
   * Otherwise, carry on. */
  private def checkNoCollectionMemberAttribute( tsv: TSVLoadFile, memberTypeOpt: Option[String] )(op: => Unit): Unit = {
    if( memberTypeOpt.isDefined && tsv.headers.contains(memberTypeOpt.get + "_id") ) {
      requestContext.complete( HttpResponse(BadRequest,
        "Can't set collection members along with other attributes; please use two-column TSV format or remove " +
          memberTypeOpt.get + "_id from your tsv.") )
    } else {
      op
    }
  }

  /**
   * Verifies that the provided list of headers includes all attributes required by the schema for this entity type.
   * Bails with a 400 Bad Request if the entity type is unknown or if some attributes are missing.
   * Returns the list of required attributes if all is well. */
  private def withRequiredAttributes(entityType: String, headers: Seq[String])(op: (Map[String, String] => Unit)):Unit = {
    ModelSchema.getRequiredAttributes(entityType) match {
      case Failure(regret) => requestContext.complete(HttpResponse(BadRequest, regret.getMessage))
      case Success(requiredAttributes) => {
        if( !requiredAttributes.keySet.subsetOf(headers.toSet) ) {
          requestContext.complete( HttpResponse(BadRequest,
            "TSV is missing required attributes: " + (requiredAttributes.keySet -- headers).mkString(", ")) )
        } else {
          op(requiredAttributes)
        }
      }
    }
  }

  def batchCallToRawls( workspaceNamespace: String, workspaceName: String, calls: Seq[EntityUpdateDefinition], endpoint: String ) = {
    log.info("TSV upload request received")
    val pipeline: HttpRequest => Future[HttpResponse] =
      authHeaders(requestContext) ~> sendReceive
    val responseFuture: Future[HttpResponse] = pipeline {
      Post(FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)+"/"+endpoint,
            HttpEntity(MediaTypes.`application/json`,calls.toJson.toString))
    }

    responseFuture onComplete {
      case Success(response) =>
        response.status match {
          case NoContent =>
            log.debug("OK response")
            requestContext.complete(OK)
          case _ =>
            // Bubble up all other unmarshallable responses
            log.warning("Unanticipated response: " + response.status.defaultMessage)
            requestContext.complete(response)
        }
      case Failure(error) =>
        // Failure accessing service
        log.error(error, "Service API call failed")
        requestContext.complete(StatusCodes.InternalServerError, error.getMessage)
    }
  }

  val upsertAttrOperation = "op" -> AttributeString("AddUpdateAttribute")
  val removeAttrOperation = "op" -> AttributeString("RemoveAttribute")
  val addListMemberOperation = "op" -> AttributeString("AddListMember")

  /**
   * colInfo is a list of (headerName, refType), where refType is the type of the entity if the headerName is an AttributeRef
   * e.g. on TCGA Pairs, there's a header called case_sample_id where the refType would be Sample */
  def setAttributesOnEntity(entityType: String, memberTypeOpt: Option[String], row: Seq[String], colInfo: Seq[(String,Option[String])]) = {
    //Iterate over the attribute names and their values
    //I (hussein) think the refTypeOpt.isDefined is to ensure that if required attributes are left empty, the empty
    //string gets passed to Rawls, which should error as they're required?
    val ops = for { (value,(attributeName,refTypeOpt)) <- row.tail zip colInfo if refTypeOpt.isDefined || !value.isEmpty } yield {
      val nameEntry = "attributeName" -> AttributeString(attributeName)
      def valEntry( attr: Attribute ) = "addUpdateAttribute" -> attr
      refTypeOpt match {
        case Some(refType) => Map(upsertAttrOperation,nameEntry,valEntry(AttributeReference(refType,value)))
        case None => value match {
          case "__DELETE__" => Map(removeAttrOperation,nameEntry)
          case _ => Map(upsertAttrOperation,nameEntry,valEntry(AttributeString(value)))
        }
      }
    }

    //If we're upserting a collection type entity, add an AddListMember( members_attr, null ) operation.
    //This will force the members_attr attribute to exist if it's being created for the first time.
    val collectionMemberAttrOp: Option[Map[String, Attribute]] =
    if( ModelSchema.isCollectionType(entityType).getOrElse(false) ) {
      val membersAttributeName = ModelSchema.getPlural(memberTypeOpt.get).get
      Some(Map(
        addListMemberOperation,
        "attributeListName"->AttributeString(membersAttributeName),
        "newMember"->AttributeNull()))
    } else {
      None
    }
    EntityUpdateDefinition(row.headOption.get,entityType,ops ++ collectionMemberAttrOp )
  }

  private def validateMembershipTSV(tsv: TSVLoadFile, membersType: Option[String]) (op: => Unit): Unit = {
    //This magical list of conditions determines whether the TSV is populating the "members" attribute of a collection type entity.
    if( !membersType.isDefined ) {
      requestContext.complete(
        HttpResponse(BadRequest,"Invalid membership TSV. Entity type must be a collection type") )
    } else if( tsv.headers.length != 2 ){
      requestContext.complete(
        HttpResponse(BadRequest, "Invalid membership TSV. Must have exactly two columns") )
    } else if( tsv.headers != Seq(tsv.firstColumnHeader, membersType.get + "_id") ) {
      requestContext.complete(
        HttpResponse(BadRequest, "Invalid membership TSV. Second column header should be " + membersType.get + "_id") )
    } else {
      op
    }
  }

  /**
   * Imports collection members into a collection type entity. */
  private def importMembershipTSV(workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, entityType: String ): Unit = {
    withMemberCollectionType(entityType) { memberTypeOpt =>
      validateMembershipTSV(tsv, memberTypeOpt) {
        withPlural(memberTypeOpt.get) { memberPlural =>
          val rawlsCalls = tsv.tsvData groupBy(_(0)) map { case (entityName, rows) =>
            val ops = rows map { row =>
              //row(1) is the entity to add as a member of the entity in row.head
              val attrRef = AttributeReference(memberTypeOpt.get,row(1))
              Map(addListMemberOperation,"attributeListName"->AttributeString(memberPlural),"newMember"->attrRef)
            }
            EntityUpdateDefinition(entityName,entityType,ops)
          }
          batchCallToRawls(workspaceNamespace, workspaceName, rawlsCalls.toSeq, "batchUpsert")
        }
      }
    }
  }

  /**
   * Creates or updates entities from an entity TSV. Required attributes must exist in column headers. */
  private def importEntityTSV(workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, entityType: String ): Unit = {
    //we're setting attributes on a bunch of entities
    checkFirstColumnsDistinct(tsv, "entity") {
      withMemberCollectionType(entityType) { memberTypeOpt =>
        checkNoCollectionMemberAttribute(tsv, memberTypeOpt) {
          withRequiredAttributes(entityType, tsv.headers) { requiredAttributes =>
            val colInfo = tsv.headers.tail map { colName => (colName, requiredAttributes.get(colName))}
            val rawlsCalls = tsv.tsvData.map(row => setAttributesOnEntity(entityType, memberTypeOpt, row, colInfo))
            batchCallToRawls(workspaceNamespace, workspaceName, rawlsCalls, "batchUpsert")
          }
        }
      }
    }
  }

  /**
   * Updates existing entities from TSV. All entities must already exist. */
  private def importUpdateTSV(workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile, entityType: String ): Unit = {
    //we're setting attributes on a bunch of entities
    checkFirstColumnsDistinct(tsv, "entity") {
      withMemberCollectionType(entityType) { memberTypeOpt =>
        checkNoCollectionMemberAttribute(tsv, memberTypeOpt) {
          ModelSchema.getRequiredAttributes(entityType) match {
            //Required attributes aren't required to be headers in update TSVs - they should already have been
            //defined when the entity was created. But we still need the type information if the headers do exist.
            case Failure(regret) => requestContext.complete(HttpResponse(BadRequest, regret.getMessage))
            case Success(requiredAttributes) =>
              val colInfo = tsv.headers.tail map { colName => (colName, requiredAttributes.get(colName))}
              val rawlsCalls = tsv.tsvData.map(row => setAttributesOnEntity(entityType, memberTypeOpt, row, colInfo))
              batchCallToRawls(workspaceNamespace, workspaceName, rawlsCalls, "batchUpdate")
          }
        }
      }
    }
  }

  /**
   * Determines the TSV type from the first column header and routes it to the correct import function. */
  def importEntitiesFromTSV(workspaceNamespace: String, workspaceName: String, tsvString: String): Unit = {
    withTSVFile(tsvString) { tsv =>
      //the first column header of the tsv defines:
      // 1. The type of the tsv import file (membership, entity, or update)
      // 2. The entity type we're trying to import or update, which is magically verified in the withFoo functions below.
      tsv.firstColumnHeader.split(":") match {
        case Array( tsvType, entityHeader ) =>
          val entityType = entityHeader.stripSuffix("_id")
          if( entityType == entityHeader ) {
            requestContext.complete(HttpResponse(BadRequest, "Invalid first column header, entity type should end in _id"))
          } else {
            tsvType match {
              case "membership" => importMembershipTSV(workspaceNamespace, workspaceName, tsv, entityType)
              case "entity" => importEntityTSV(workspaceNamespace, workspaceName, tsv, entityType)
              case "update" => importUpdateTSV(workspaceNamespace, workspaceName, tsv, entityType)
              case _ => requestContext.complete(
                HttpResponse(BadRequest, "Invalid TSV type, supported types are: membership, entity, update"))
            }
          }
        case _ => requestContext.complete(
          HttpResponse(BadRequest, "Invalid first column header, should look like tsvType:entity_type_id"))
      }
    }
  }
}
