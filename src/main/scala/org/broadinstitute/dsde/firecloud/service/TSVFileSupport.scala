package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage
import org.broadinstitute.dsde.firecloud.utils.{TSVLoadFile, TSVParser}
import akka.http.scaladsl.model.StatusCodes._
import spray.json._
import DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * The different types of tsv import/export formats
 */
object TsvTypes {
  sealed trait TsvType
  case object ENTITY extends TsvType { override def toString = "entity" } // insert or update, must have required columns
  case object UPDATE extends TsvType { override def toString = "update" } // update only, entity must preexist
  case object MEMBERSHIP extends TsvType { override def toString = "membership" } // add members to a set

  def withName(name: String): TsvType = {
    name match {
      case "entity" => ENTITY
      case "update" => UPDATE
      case "membership" => MEMBERSHIP
      case _ => throw new FireCloudException(s"Invalid TSV type '$name', supported types are: membership, entity, update")
    }
  }
}

trait TSVFileSupport {
  /**
    * Attempts to parse a string into a TSVLoadFile.
    * Bails with a 400 Bad Request if the TSV is invalid. */
  def withTSVFile(tsvString:String)(op: TSVLoadFile => Future[PerRequestMessage])(implicit ec: ExecutionContext): Future[PerRequestMessage] = {
    Try(TSVParser.parse(tsvString)) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
      case Success(tsvFile) => op(tsvFile)
    }
  }

  def checkNumberOfRows(tsv: TSVLoadFile, rows: Int)(op: => Future[PerRequestMessage])(implicit ec: ExecutionContext): Future[PerRequestMessage] = {
    if ((tsv.tsvData.length + (if (tsv.headers.isEmpty) 0 else 1)) != rows) {
      Future(RequestCompleteWithErrorReport(BadRequest,
        "Your file does not have the correct number of rows. There should be " + rows.toString))
    } else {
      op
    }
  }

  def checkFirstRowDistinct( tsv: TSVLoadFile )(op: => Future[PerRequestMessage])(implicit ec: ExecutionContext): Future[PerRequestMessage] = {
    val attributeNames = Seq(tsv.headers.head.stripPrefix("workspace:")) ++ tsv.headers.tail
    if (attributeNames.size != attributeNames.distinct.size) {
      Future(RequestCompleteWithErrorReport(BadRequest, "Duplicated attribute keys are not allowed"))
    } else {
      op
    }
  }

  /**
    * Bail with a 400 Bad Request if the first column of the tsv has duplicate values.
    * Otherwise, carry on. */
  def checkFirstColumnDistinct( tsv: TSVLoadFile )(op: => Future[PerRequestMessage])(implicit ec: ExecutionContext): Future[PerRequestMessage] = {
    val entitiesToUpdate = tsv.tsvData.map(_.headOption.get)
    val distinctEntities = entitiesToUpdate.distinct
    if ( entitiesToUpdate.size != distinctEntities.size ) {
      Future( RequestCompleteWithErrorReport(BadRequest,
        "Duplicated entities are not allowed in TSV: " + entitiesToUpdate.diff(distinctEntities).distinct.mkString(", ")) )
    } else {
      op
    }
  }

  /**
    * Collection type entities have typed members enforced by the schema. If the provided entity type exists, returns
    * Some( its_member_type ) if it's a collection, or None if it isn't.
    * Bails with a 400 Bad Request if the provided entity type is unknown to the schema. */
  def withMemberCollectionType(entityType: String, modelSchema: ModelSchema)(op: Option[String] => Future[PerRequestMessage])(implicit ec: ExecutionContext): Future[PerRequestMessage] = {
    modelSchema.getCollectionMemberType(entityType) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
      case Success(memberTypeOpt) => op(memberTypeOpt)
    }
  }

  /**
    * Bail with a 400 Bad Request if the tsv is trying to set members on a collection type.
    * Otherwise, carry on. */
  def checkNoCollectionMemberAttribute( tsv: TSVLoadFile, memberTypeOpt: Option[String] )(op: => Future[PerRequestMessage])(implicit ec: ExecutionContext): Future[PerRequestMessage] = {
    if( memberTypeOpt.isDefined && tsv.headers.contains(memberTypeOpt.get + "_id") ) {
      Future( RequestCompleteWithErrorReport(BadRequest,
        "Can't set collection members along with other attributes; please use two-column TSV format or remove " +
          memberTypeOpt.get + "_id from your tsv.") )
    } else {
      op
    }
  }

  def validateMembershipTSV(tsv: TSVLoadFile, membersType: Option[String]) (op: => Future[PerRequestMessage])(implicit ec: ExecutionContext): Future[PerRequestMessage] = {
    //This magical list of conditions determines whether the TSV is populating the "members" attribute of a collection type entity.
    if( membersType.isEmpty ) {
      Future(
        RequestCompleteWithErrorReport(BadRequest,"Invalid membership TSV. Entity type must be a collection type") )
    } else if( tsv.headers.length != 2 ){
      Future(
        RequestCompleteWithErrorReport(BadRequest, "Invalid membership TSV. Must have exactly two columns") )
    } else if( tsv.headers != Seq(tsv.firstColumnHeader, membersType.get) ) {
      Future(
        RequestCompleteWithErrorReport(BadRequest, "Invalid membership TSV. Second column header should be " + membersType.get) )
    } else {
      op
    }
  }

  /*
  Takes a TSVLoadFile for **workspace attributes** and turns it into sequence of AttributeUpdateOperation
   */
  def getWorkspaceAttributeCalls(tsv: TSVLoadFile): Seq[AttributeUpdateOperation] = {
    val attributePairs = (Seq(tsv.headers.head.stripPrefix("workspace:")) ++ tsv.headers.tail).zip(tsv.tsvData.head)
    attributePairs.map { case (name, value) =>
      if (value.equals("__DELETE__"))
        RemoveAttribute(AttributeName.fromDelimitedName(name))
      else {
        AddUpdateAttribute(AttributeName.fromDelimitedName(name), AttributeString(StringContext.treatEscapes(value)))
      }
    }
  }


  val upsertAttrOperation: (String, AttributeString) = "op" -> AttributeString("AddUpdateAttribute")
  val removeAttrOperation: (String, AttributeString) = "op" -> AttributeString("RemoveAttribute")
  val addListMemberOperation: (String, AttributeString) = "op" -> AttributeString("AddListMember")
  val createRefListOperation: (String, AttributeString) = "op" -> AttributeString("CreateAttributeEntityReferenceList")

  /**
    * colInfo is a list of (headerName, refType), where refType is the type of the entity if the headerName is an AttributeRef
    * e.g. on TCGA Pairs, there's a header called case_sample_id where the refType would be Sample */
  def setAttributesOnEntity(entityType: String, memberTypeOpt: Option[String], row: Seq[String], colInfo: Seq[(String,Option[String])], modelSchema: ModelSchema): EntityUpdateDefinition = {
    //Iterate over the attribute names and their values
    //I (hussein) think the refTypeOpt.isDefined is to ensure that if required attributes are left empty, the empty
    //string gets passed to Rawls, which should error as they're required?
    val ops = for { (value,(attributeName,refTypeOpt)) <- row.tail zip colInfo if refTypeOpt.isDefined || !value.isEmpty } yield {
      val nameEntry = "attributeName" -> AttributeString(attributeName)
      val listNameEntry = "attributeListName" -> AttributeString(attributeName)
      def valEntry( attr: Attribute ) = "addUpdateAttribute" -> attr
      def listValEntry( attr: Attribute ) = "newMember" -> attr
      refTypeOpt match {
        case Some(refType) => Seq(Map(upsertAttrOperation,nameEntry,valEntry(AttributeEntityReference(refType,value))))
        case None => value match {
          case "__DELETE__" => Seq(Map(removeAttrOperation,nameEntry))
          case value if modelSchema.isAttributeArray(value) => {
            val listElements = value.parseJson.convertTo[JsArray].elements.toList

            listElements match {
              case elements if elements.forall(_.isInstanceOf[JsString]) =>
                val typed: List[JsString] = elements.asInstanceOf[List[JsString]]
                typed.map(jsstr => Map(addListMemberOperation, listNameEntry, listValEntry(AttributeString(jsstr.value))))

              case elements if elements.forall(_.isInstanceOf[JsNumber]) =>
                val typed: List[JsNumber] = elements.asInstanceOf[List[JsNumber]]
                typed.map(jsnum => Map(addListMemberOperation, listNameEntry, listValEntry(AttributeNumber(jsnum.value))))

              case elements if elements.forall(_.isInstanceOf[JsBoolean]) =>
                val typed: List[JsBoolean] = elements.asInstanceOf[List[JsBoolean]]
                typed.map(jsbool => Map(addListMemberOperation, listNameEntry, listValEntry(AttributeBoolean(jsbool.value))))

              case _ => throw new FireCloudException("Mixed-type entity attribute lists are not supported.")
            }
          }
          case _ => Seq(Map(upsertAttrOperation,nameEntry,valEntry(AttributeString(value))))
        }
      }
    }

    //If we're upserting a collection type entity, add an AddListMember( members_attr, null ) operation.
    //This will force the members_attr attribute to exist if it's being created for the first time.
    val collectionMemberAttrOp: Option[Map[String, Attribute]] =
    if (modelSchema.isCollectionType(entityType)) {
      val membersAttributeName = modelSchema.getPlural(memberTypeOpt.get).get
      Some(Map(
        createRefListOperation,
        "attributeListName"->AttributeString(membersAttributeName)))
    } else {
      None
    }
    EntityUpdateDefinition(row.headOption.get,entityType,ops.flatten ++ collectionMemberAttrOp )
  }

}
