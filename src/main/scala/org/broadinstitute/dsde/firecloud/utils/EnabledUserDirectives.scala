package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives.extractUri
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.broadinstitute.dsde.workbench.client.sam.api.UsersApi
import org.broadinstitute.dsde.workbench.client.sam.{ApiClient, ApiException}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait EnabledUserDirectives extends LazyLogging  with SprayJsonSupport {

  /**
    * Queries Sam to see if the current user is enabled. If the user is disabled,
    * responds with Forbidden and prevents the rest of the route from executing.
    *
    * @param userInfo credentials for the current user
    * @param samBaseUrl where to find Sam - used for unit testing
    * @return n/a
    */
  def requireEnabledUser(userInfo: UserInfo, samBaseUrl: String = FireCloudConfig.Sam.baseUrl)(innerRoute: RequestContext => Future[RouteResult]): Route = {
    extractUri { uri =>
      Try(getUserEnabled(userInfo.accessToken.token, samBaseUrl)) match {
        case Success(true) =>
          logger.debug(s"User ${userInfo.userEmail} is enabled: $uri")
          innerRoute
        case Success(false) =>
          logger.warn(s"User ${userInfo.userEmail} is disabled: $uri")
          // TODO: do we want a more helpful message like "user is disabled"?
          throwErrorReport(StatusCodes.Forbidden, StatusCodes.Forbidden.defaultMessage)
        case Failure(ex) => ex match {
          case apiex:ApiException =>
            logger.error(s"Exception checking enabled status for user ${userInfo.userEmail}: (${apiex.getMessage}) while calling $uri", apiex)
            val code = StatusCode.int2StatusCode(apiex.getCode)
            throwErrorReport(code, apiex.getMessage)
          case ex =>
            logger.error(s"Exception checking enabled status for user ${userInfo.userEmail}: (${ex.getMessage}) while calling $uri", ex)
            throwErrorReport(StatusCodes.InternalServerError, ex.getMessage)
        }
      }
    }
  }

  private def getUserEnabled(token: String, samBaseUrl: String): Boolean = {
    val apiClient = new ApiClient()
    apiClient.setAccessToken(token)
    apiClient.setBasePath(samBaseUrl)
    val sam = new UsersApi(apiClient)
    val userStatus = sam.getUserStatusInfo
    // TODO: is it enough to check this one `getEnabled` boolean?
    // TODO: retries?
    userStatus.getEnabled
  }

  /**
    * Constructs and throws a FireCloudExceptionWithErrorReport in response to the
    * enabled-user check. Hardcodes an ErrorReportSource to allow differentiating
    * between enabled-user errors and other errors.
    *
    * @param statusCode the http status code to throw
    * @param message message to use in the thrown exception
    * @return nothing
    */
  private def throwErrorReport(statusCode: StatusCode, message: String): Nothing = {
    val errRpt = ErrorReport(statusCode, message)(ErrorReportSource("enabled-check"))
    throw new FireCloudExceptionWithErrorReport(errRpt)
  }

}
