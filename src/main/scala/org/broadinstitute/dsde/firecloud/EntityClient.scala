package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Try, Failure, Success}

import akka.actor.{Actor, Props}
import akka.event.Logging
import spray.client.pipelining._
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.http._
import spray.routing.RequestContext
import spray.json._
import spray.json.DefaultJsonProtocol._

import org.broadinstitute.dsde.firecloud.EntityClient._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{EntityCreateResult, ModelSchema}
import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile

object EntityClient {
  case class EntityListRequest(workspaceNamespace: String,
                               workspaceName: String,
                               entityType: String)

  case class CreateEntities(workspaceNamespace: String,
                            workspaceName: String,
                            entities: JsValue)

  case class UpsertEntitiesFromTSV(workspaceNamespace: String,
                                   workspaceName: String,
                                   tsvFile: TSVLoadFile)

  def props(requestContext: RequestContext): Props = Props(new EntityClient(requestContext))

}

class EntityClient (requestContext: RequestContext) extends Actor {

  import system.dispatcher

  implicit val system = context.system
  val log = Logging(system, getClass)
  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {
    case EntityListRequest(workspaceNamespace: String, workspaceName: String, entityType: String) =>
      listEntities(workspaceNamespace, workspaceName, entityType)
    case CreateEntities(workspaceNamespace: String, workspaceName: String, entities: JsValue) =>
      createEntities(workspaceNamespace, workspaceName, entities)
    case UpsertEntitiesFromTSV(workspaceNamespace: String, workspaceName: String, tsvFile: TSVLoadFile) =>
      upsertEntitiesFromTSV(workspaceNamespace, workspaceName, tsvFile)
  }

  def listEntities(workspaceNamespace: String, workspaceName: String, entityType: String): Unit = {
    log.info("listEntities request received")
    val pipeline: HttpRequest => Future[HttpResponse] =
      addHeader(Cookie(requestContext.request.cookies)) ~> sendReceive
    val responseFuture: Future[HttpResponse] = pipeline {
      Get(s"${FireCloudConfig.Workspace.entityPathFromWorkspace(workspaceNamespace, workspaceName)}/$entityType")
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
    val url = FireCloudConfig.Workspace.entityPathFromWorkspace(workspaceNamespace, workspaceName)
    val externalRequest = Post(url, HttpClient.createJsonHttpEntity(entityJson.compactPrint))
    val pipeline = addHeader(Cookie(requestContext.request.cookies)) ~> sendReceive
    // TODO figure out how to get the response in a non-blocking way,
    // TODO given that the requestContext should not complete until *all* are finished?
    Try(Await.result(pipeline(externalRequest), Duration.Inf)) match {
      case Success(response) => response.status match {
        case StatusCodes.Created => EntityCreateResult(entityType, entityName, true, "Entity created successfully")
        case _ => EntityCreateResult(entityType, entityName, false, s"Bad response from workspace service: ${response.message}")
      }
      case Failure(e: Exception) => EntityCreateResult(entityType, entityName, false,
        s"Error sending request to workspace service: ${e.getMessage}")
    }
  }

  //Collection type entities have typed members enforced by the schema. If the provided entity type exists, returns
  //Some( its_member_type ) if it's a collection, or None if it isn't.
  //Bails with a 400 Bad Request if the provided entity type is unknown to the schema.
  private def withMemberCollectionType(entityType: String)(op: (Option[String] => Unit)): Unit = {
    ModelSchema.getCollectionMemberType(entityType) match {
      case Failure(regret) => requestContext.complete(HttpResponse(BadRequest, regret.getMessage))
      case Success(memberTypeOpt) => op(memberTypeOpt)
    }
  }

  //Returns the plural form of the entity type.
  //Bails with a 400 Bad Request if the entity type is unknown to the schema.
  private def withPlural(entityType: String)(op: (String => Unit)): Unit = {
    ModelSchema.getPlural(entityType) match {
      case Failure(regret) => requestContext.complete(HttpResponse(BadRequest, regret.getMessage))
      case Success(plural) => op(plural)
    }
  }

  //Bail with a 400 Bad Request if the first column of the tsv has duplicate values.
  //Otherwise, carry on.
  private def checkFirstColumnsDistinct( tsv: TSVLoadFile )(op: Unit): Unit = {
    val entitiesToUpdate = tsv.tsvData.map(_(tsv.firstColumnHeader))
    val distinctEntities = entitiesToUpdate.distinct
    if( entitiesToUpdate != distinctEntities ) {
      requestContext.complete( HttpResponse(BadRequest, "TSV has duplicated entities: " + entitiesToUpdate.diff(distinctEntities).distinct.mkString(", ")) )
    } else {
      op
    }
  }

  //Bail with a 400 Bad Request if the tsv is trying to set members on a collection type.
  //Otherwise, carry on.
  private def checkNoCollectionMemberAttribute( tsv: TSVLoadFile, memberTypeOpt: Option[String] )(op: Unit): Unit = {
    if( memberTypeOpt.isDefined && tsv.headers.contains(memberTypeOpt.get + "_id") ) {
      requestContext.complete( HttpResponse(BadRequest, "Can't set collection members along with other attributes; please use two-column TSV format or remove " + memberTypeOpt.get + "_id from your tsv.") )
    } else {
      op
    }
  }

  //Verifies that the provided list of headers includes all attributes required by the schema for this entity type.
  //Bails with a 400 Bad Request if the entity type is unknown or if some attributes are missing.
  //Returns the list of required attributes if all is well.
  private def withRequiredAttributes(entityType: String, headers: Seq[String])(op: (Map[String, String] => Unit)):Unit = {
    ModelSchema.getRequiredAttributes(entityType) match {
      case Failure(regret) => requestContext.complete(HttpResponse(BadRequest, regret.getMessage))
      case Success(requiredAttributes) => {
        if( !requiredAttributes.keySet.subsetOf(headers.toSet) ) {
          requestContext.complete( HttpResponse(BadRequest, "TSV is missing required attributes: " + (requiredAttributes.keySet -- headers).mkString(", ")) )
        } else {
          op(requiredAttributes)
        }
      }
    }
  }

  def addToCollectionMembersAttribute( entityType: String, entityName: String, newMembers: Seq[(String, String)], membersAttributeName: String ) = {
    //STUB: return a partial batch Rawls call for this:
    //(entityName, entityType).attrs[membersAttributeName].extend(newMembers)
  }

  def setAttributesOnEntity(entityType: String, entityName: String, attributesMap: Map[String, String], referenceTypes: Map[String, String] ) = {
    //for all attributes in attributesMap
    // if the key is present in referenceTypes, the value is an entity name and referenceTypes(key) contains that entity's type
    //MAYBE-DO: Parse out types provided in the TSV header? Or allow the model to specify types for optionally specified attributes?

    //STUB: return a batch of AddUpdateOperations
  }

  def batchCallToRawls( calls: Any ) = {
    //STUB: stitch the batch calls together and fire off the Rawls request.
    //requestContext.complete(...)
  }

  def addToCollectionTypeFromTSV( workspaceNamespace: String, workspaceName: String, entityType: String, memberType: String, membersAttributeName: String, tsv: TSVLoadFile ) = {
    //group together updates to distinct entities, construct the list of entities to add references to, and poke it into whatever the "members" field is called.
    val tsvFieldName = memberType + "_id"
    tsv.tsvData.groupBy( _(tsv.firstColumnHeader) ).map({
      case (entityName, rows) => addToCollectionMembersAttribute( entityType, entityName, rows.map(row => (memberType, row(tsvFieldName))), membersAttributeName )
    })
  }

  private def tsvPopulatesCollectionMembers(tsv: TSVLoadFile, membersType: Option[String]): Boolean = {
    //This magical list of conditions determines whether the TSV is populating the "members" attribute of a collection type entity.
    membersType.isDefined &&
      tsv.headers.length == 2 &&
      tsv.headers == Seq( tsv.firstColumnHeader, membersType.get + "_id" )
  }

  //TSVs can be of two forms: they can either populate the "members" list of a collection entity,
  //or they can populate the attributes on an(y kind of) entity.
  def upsertEntitiesFromTSV(workspaceNamespace: String, workspaceName: String, tsv: TSVLoadFile): Unit = {
    //the first column header of the tsv defines the entity type we're trying to import or update.
    //This is magically verified in the withFoo functions below.
    val entityType = tsv.firstColumnHeader.stripSuffix("_id")

    withMemberCollectionType(entityType) { memberTypeOpt =>

      if( tsvPopulatesCollectionMembers(tsv, memberTypeOpt) ) {

        withPlural(memberTypeOpt.get) { memberPlural =>
          val rawlsCalls = addToCollectionTypeFromTSV(workspaceNamespace, workspaceName, entityType, memberTypeOpt.get, memberPlural, tsv)
          batchCallToRawls(rawlsCalls)
        }
      } else {
        //we're setting attributes on a bunch of entities
        checkFirstColumnsDistinct(tsv) {
          checkNoCollectionMemberAttribute(tsv, memberTypeOpt) {
            withRequiredAttributes(entityType, tsv.headers) { requiredAttributes =>
              val rawlsCalls = tsv.tsvData.map(row => setAttributesOnEntity(entityType, row(tsv.firstColumnHeader), row - tsv.firstColumnHeader, requiredAttributes))
              batchCallToRawls(rawlsCalls)
            }
          }
        }
      }
    }
  }
}
