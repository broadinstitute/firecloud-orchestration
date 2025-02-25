package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives.{extractUri, onComplete}
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.SamDAO
import org.broadinstitute.dsde.firecloud.model.{UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.broadinstitute.dsde.workbench.client.sam.api.UsersApi
import org.broadinstitute.dsde.workbench.client.sam.model.UserStatusInfo
import org.broadinstitute.dsde.workbench.client.sam.{ApiCallback, ApiClient, ApiException}
import org.broadinstitute.dsde.workbench.util.FutureSupport.toFutureTry

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

trait EnabledUserDirectives extends LazyLogging with SprayJsonSupport with StatusCodeUtils {

  // Hardcode an ErrorReportSource to allow differentiating between enabled-user errors and other errors.
  implicit val errorReportSource: ErrorReportSource = ErrorReportSource("Orchestration-enabled-check")
  implicit val executionContext: ExecutionContext
  implicit val materializer: Materializer

  val samDao: SamDAO

  /**
    * Queries Sam to see if the current user is enabled. If the user is disabled,
    * responds with Forbidden and prevents the rest of the route from executing.
    *
    * @param userInfo credentials for the current user
    * @param samBaseUrl where to find Sam - used for unit testing
    * @return n/a
    */
  def requireEnabledUser(userInfo: UserInfo)(
    innerRoute: RequestContext => Future[RouteResult]
  ): Route =
    extractUri { uri =>
      onComplete(getUserEnabled(userInfo)) {
        case Success(true) =>
          logger.debug(s"User ${userInfo.userEmail} is enabled: $uri")
          innerRoute
        case Success(false) =>
          logger.warn(s"User ${userInfo.userEmail} is disabled: $uri")
          // the 401/"User is disabled." response mirrors what Sam returns in this case.
          throwErrorReport(StatusCodes.Unauthorized, "User is disabled.")
        case Failure(fcerr: FireCloudExceptionWithErrorReport)
            if fcerr.errorReport.statusCode.contains(StatusCodes.NotFound) =>
          throwErrorReport(StatusCodes.Unauthorized, "User is not registered.")
        case Failure(fcerr: FireCloudExceptionWithErrorReport) =>
          logger.error(
            s"FireCloudExceptionWithErrorReport exception checking enabled status for user ${userInfo.userEmail}: (${fcerr.getMessage}) while calling $uri",
            fcerr
          )
          // rebuild the FireCloudExceptionWithErrorReport to ensure we're not passing along stack traces
          val code = fcerr.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError)
          throwErrorReport(code, fcerr.getMessage)
        case Failure(apiex: ApiException) =>
          logger.error(
            s"ApiException exception checking enabled status for user ${userInfo.userEmail}: (${apiex.getMessage}) while calling $uri",
            apiex
          )
          val code = statusCodeFrom(apiex.getCode, Option(StatusCodes.InternalServerError))
          val message =
            if (Option(apiex.getMessage).isEmpty || apiex.getMessage.isEmpty) code.defaultMessage()
            else apiex.getMessage
          throwErrorReport(code, message)
        case Failure(ex) =>
          logger.error(
            s"Unexpected exception checking enabled status for user ${userInfo.userEmail}: (${ex.getMessage}) while calling $uri",
            ex
          )
          throwErrorReport(StatusCodes.InternalServerError, ex.getMessage)
      }
    }

  private def getUserEnabled(user: WithAccessToken): Future[Boolean] =
    samDao.getUserStatus(user) map (userStatus => Boolean.unbox(userStatus.getEnabled))

  /**
    * Constructs and throws a FireCloudExceptionWithErrorReport in response to the
    * enabled-user check.
    *
    * @param statusCode the http status code to throw
    * @param message message to use in the thrown exception
    * @return nothing
    */
  private def throwErrorReport(statusCode: StatusCode, message: String): Nothing = {
    val errRpt = ErrorReport(statusCode, message)
    throw new FireCloudExceptionWithErrorReport(errRpt)
  }

}
