package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, extractUri}
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.ErrorReportFormat
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.broadinstitute.dsde.workbench.client.sam.api.UsersApi
import org.broadinstitute.dsde.workbench.client.sam.{ApiClient, ApiException}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait EnabledUserDirectives extends LazyLogging  with SprayJsonSupport {

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource("enabled-check")

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
          logger.debug(s"User ${userInfo.userEmail} is enabled: ${uri}")
          innerRoute
        case Success(false) =>
          logger.warn(s"User ${userInfo.userEmail} is disabled: ${uri}")
          throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, StatusCodes.Forbidden.defaultMessage))
          // complete(StatusCodes.Forbidden) // TODO: do we want a more helpful message like "user is disabled"?
        case Failure(ex) => ex match {
          case apiex:ApiException =>
            logger.error(s"Exception checking enabled status for user ${userInfo.userEmail}: (${apiex.getMessage}) while calling ${uri}", apiex)
            val code = StatusCode.int2StatusCode(apiex.getCode)
            throw new FireCloudExceptionWithErrorReport(ErrorReport(code, apiex.getMessage))
//            complete(code, ErrorReport(code, apiex.getMessage))
          case ex =>
            logger.error(s"Exception checking enabled status for user ${userInfo.userEmail}: (${ex.getMessage}) while calling ${uri}", ex)
            throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, ex.getMessage))
//            complete(StatusCodes.InternalServerError, ErrorReport(StatusCodes.InternalServerError, ex.getMessage))
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
    userStatus.getEnabled
  }

}
