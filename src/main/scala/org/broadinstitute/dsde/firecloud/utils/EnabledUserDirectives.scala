package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives.{extractUri, onComplete}
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.broadinstitute.dsde.workbench.client.sam.api.UsersApi
import org.broadinstitute.dsde.workbench.client.sam.model.UserStatusInfo
import org.broadinstitute.dsde.workbench.client.sam.{ApiCallback, ApiClient, ApiException}
import org.broadinstitute.dsde.workbench.util.FutureSupport.toFutureTry

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

trait EnabledUserDirectives extends LazyLogging  with SprayJsonSupport {

  // Hardcode an ErrorReportSource to allow differentiating between enabled-user errors and other errors.
  implicit val errorReportSource: ErrorReportSource = ErrorReportSource("Orchestration-enabled-check")
  implicit val executionContext: ExecutionContext
  implicit val materializer: Materializer

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
      onComplete(getUserEnabled(userInfo.accessToken.token, samBaseUrl)) {
        case Success(true) =>
          logger.debug(s"User ${userInfo.userEmail} is enabled: $uri")
          innerRoute
        case Success(false) =>
          logger.warn(s"User ${userInfo.userEmail} is disabled: $uri")
          // the 401/"User is disabled." response mirrors what Sam returns in this case.
          throwErrorReport(StatusCodes.Unauthorized, "User is disabled.")
        case Failure(fcerr: FireCloudExceptionWithErrorReport) =>
          logger.error(s"FireCloudExceptionWithErrorReport exception checking enabled status for user ${userInfo.userEmail}: (${fcerr.getMessage}) while calling $uri", fcerr)
          // rebuild the FireCloudExceptionWithErrorReport to ensure we're not passing along stack traces
          val code = fcerr.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError)
          throwErrorReport(code, fcerr.getMessage)
        case Failure(apiex:ApiException) =>
          logger.error(s"ApiException exception checking enabled status for user ${userInfo.userEmail}: (${apiex.getMessage}) while calling $uri", apiex)
          val code = StatusCode.int2StatusCode(apiex.getCode)
          if (code == StatusCodes.NotFound) {
            throwErrorReport(StatusCodes.Unauthorized, "User is not registered.")
          } else {
            val message = if (Option(apiex.getMessage).isEmpty || apiex.getMessage.isEmpty) code.defaultMessage() else apiex.getMessage
            throwErrorReport(code, message)
          }
        case Failure(ex) =>
          logger.error(s"Unexpected exception checking enabled status for user ${userInfo.userEmail}: (${ex.getMessage}) while calling $uri", ex)
          throwErrorReport(StatusCodes.InternalServerError, ex.getMessage)
      }
    }
  }


  private class SamApiCallback[T](functionName: String = "userStatusInfo") extends ApiCallback[T] {
    private val promise = Promise[T]()

    override def onFailure(e: ApiException, statusCode: Int, responseHeaders: java.util.Map[String, java.util.List[String]]): Unit = {
      val response = e.getResponseBody
      // attempt to propagate an ErrorReport from Sam. If we can't understand Sam's response as an ErrorReport,
      // create our own error message.
      import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.ErrorReportFormat
      toFutureTry(Unmarshal(response).to[ErrorReport]) flatMap {
        case Success(err) =>
          logger.error(s"Sam call to $functionName failed with error $err")
          throw new FireCloudExceptionWithErrorReport(err)
        case Failure(_) =>
          // attempt to extract something useful from the response entity, even though it's not an ErrorReport
          toFutureTry(Unmarshal(response).to[String]) map { maybeString =>
            val stringErrMsg = maybeString match {
              case Success(stringErr) => stringErr
              case Failure(_) => response
            }
            throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCode.int2StatusCode(statusCode), s"Sam call to $functionName failed with error '$stringErrMsg'"))
          }
      }
      promise.failure(e)
    }
    override def onSuccess(result: T, statusCode: Int, responseHeaders: java.util.Map[String, java.util.List[String]]): Unit = promise.success(result)

    override def onUploadProgress(bytesWritten: Long, contentLength: Long, done: Boolean): Unit = ()

    override def onDownloadProgress(bytesRead: Long, contentLength: Long, done: Boolean): Unit = ()

    def future: Future[T] = promise.future
  }

  private def getUserEnabled(token: String, samBaseUrl: String): Future[Boolean] = {
    val apiClient = new ApiClient()
    apiClient.setAccessToken(token)
    apiClient.setBasePath(samBaseUrl)
    val sam = new UsersApi(apiClient)
    val callback = new SamApiCallback[UserStatusInfo]
    // TODO: should we use retries here?
    /* TODO: instead of directly calling Sam's userStatusInfo API, should we call a "real" api and, if that api returns
        401, echo its response? That would allow Orch to always give the same error response that Sam gives, but
        it is likely to be more expensive in the common case of the user being enabled.
     */
    sam.getUserStatusInfoAsync(callback)
    // TODO: is it enough to check this one `getEnabled` boolean?
    callback.future map (userStatus => Boolean.unbox(userStatus.getEnabled))
  }

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
