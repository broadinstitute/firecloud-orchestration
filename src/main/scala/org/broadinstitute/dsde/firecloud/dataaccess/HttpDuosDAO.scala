package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.DUOS.Consent
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.http.{StatusCodes, Uri}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import spray.json.JsObject

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.DUOS.{Consent, ConsentError}
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.DUOS.Consent
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.client.pipelining._
import spray.http.Uri
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}


class HttpDuosDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends DuosDAO with RestJsonClient with LazyLogging {

  implicit val errorReportSource = ErrorReportSource("DUOS")

  override def search(term: String): Future[Option[List[TermResource]]] = {
    searchBlocking(term)
  }

  @deprecated("uses blocking I/O, boo!", "2017-03-15")
  private def searchBlocking(term: String): Future[Option[List[TermResource]]] = {
    val targetUri = Uri(ontologySearchUrl).withQuery(("id", term))
    logger.info(s"HttpOntologyDAO querying ${term} ...")
    val response = Await.result(unAuthedRequest(Get(targetUri)), Duration(30, "seconds"))
    logger.info(s"HttpOntologyDAO querying ${term}: ${response.status.defaultMessage}")
    response.entity.as[List[TermResource]] match {
      case Right(obj) => Future(Some(obj))
      case Left(err) =>
        logger.warn(s"Error while retrieving ontology parents for id '$term': $err")
        Future(None)
    }
  }

  private def searchAsync(term: String): Future[Option[List[TermResource]]] = {
    val targetUri = Uri(ontologySearchUrl).withQuery(("id", term))
    logger.info(s"HttpOntologyDAO querying ${term} ...")
    unAuthedRequest(Get(targetUri)) map { response =>
      logger.info(s"HttpOntologyDAO querying ${term}: ${response.status.defaultMessage}")
      response.entity.as[List[TermResource]] match {
        case Right(obj) => Some(obj)
        case Left(err) =>
          logger.warn(s"Error while retrieving ontology parents for id '$term': $err")
          None
      }
    }
  }

  override def orspIdSearch(userInfo: UserInfo, orspId: String): Future[Option[Consent]] = {
    userAuthedRequest(Get(Uri(orspIdSearchUrl).withQuery(("name", orspId))))(userInfo) map { response =>
      val entityString = response.entity.asString
      response.status match {
        case StatusCodes.OK =>
          response.entity.as[Consent] match {
            case Right(consent) => Some(consent)
            case Left(err) =>
              logger.warn(s"Error while retrieving consent for orsp id '$orspId': $err")
              None
          }
        case x if x == StatusCodes.BadRequest || x == StatusCodes.NotFound =>
          logger.warn(s"Error while retrieving consent for orsp id '$orspId': $entityString")
          response.entity.as[ConsentError] match {
            case Right(consentError) =>
              throw new FireCloudExceptionWithErrorReport(ErrorReport(consentError.code, consentError.message))
            case Left(err) =>
              throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
          }
        case _ =>
          logger.error(response.toString)
          throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
      }
    }
  }

}
