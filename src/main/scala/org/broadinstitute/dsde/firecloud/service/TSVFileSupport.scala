package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddUpdateAttribute, AttributeUpdateOperation, RemoveAttribute}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.AttributeFormat
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage
import org.broadinstitute.dsde.firecloud.utils.{TSVLoadFile, TSVParser}
import spray.http.StatusCodes
import spray.http.StatusCodes._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import spray.json._
/**
  * Created by ansingh on 11/14/16.
  */
trait TSVFileSupport extends Actor {

  private implicit val impPlainAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer

  /**
    * Attempts to parse a string into a TSVLoadFile.
    * Bails with a 400 Bad Request if the TSV is invalid. */
  def withTSVFile(tsvString:String)(op: (TSVLoadFile => Future[PerRequestMessage]))(implicit ec: ExecutionContext): Future[PerRequestMessage] = {
    Try(TSVParser.parse(tsvString)) match {
      case Failure(regret) => Future(RequestCompleteWithErrorReport(BadRequest, regret.getMessage))
      case Success(tsvFile) => op(tsvFile)
    }
  }

  def checkNumberOfRows(tsv: TSVLoadFile, rows: Int)(op: => Future[PerRequestMessage])(implicit ec: ExecutionContext): Future[PerRequestMessage] = {
    if ((tsv.tsvData.length + (if (tsv.headers.isEmpty) 0 else 1)) != rows) {
      Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest,
        "Your file does not have the correct number of rows. There should be " + rows.toString))
    } else {
      op
    }
  }

  def checkFirstRowDistinct( tsv: TSVLoadFile )(op: => Future[PerRequestMessage])(implicit ec: ExecutionContext): Future[PerRequestMessage] = {
    val attributeNames = Seq(tsv.headers.head.stripPrefix("workspace:")) ++ tsv.headers.tail
    if (attributeNames.size != attributeNames.distinct.size) {
      Future(RequestCompleteWithErrorReport(StatusCodes.BadRequest, "Duplicated attribute keys are not allowed"))
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

  def getWorkspaceAttributeCalls(tsv: TSVLoadFile): Seq[AttributeUpdateOperation] = {
    val attributePairs = (Seq(tsv.headers.head.stripPrefix("workspace:")) ++ tsv.headers.tail).zip(tsv.tsvData.head)
    attributePairs.map { case (name, value) =>
      if (value.equals("__DELETE__"))
        new RemoveAttribute(new AttributeName("default", name))
      else {
        new AddUpdateAttribute(new AttributeName("default", name), impPlainAttributeFormat.read(value.parseJson))
      }
    }
  }


  val upsertAttrOperation = "op" -> AttributeString("AddUpdateAttribute")
  val removeAttrOperation = "op" -> AttributeString("RemoveAttribute")
  val addListMemberOperation = "op" -> AttributeString("AddListMember")
  val createRefListOperation = "op" -> AttributeString("CreateAttributeEntityReferenceList")

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
        case Some(refType) => Map(upsertAttrOperation,nameEntry,valEntry(AttributeEntityReference(refType,value)))
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
        createRefListOperation,
        "attributeListName"->AttributeString(membersAttributeName)))
    } else {
      None
    }
    EntityUpdateDefinition(row.headOption.get,entityType,ops ++ collectionMemberAttrOp )
  }

}
