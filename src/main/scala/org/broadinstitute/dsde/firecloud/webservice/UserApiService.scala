package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{Authorization, HttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpMethods, HttpResponse, StatusCode}
import akka.http.scaladsl.server.{RequestContext, RouteResult}
import akka.http.scaladsl.unmarshalling.Unmarshal
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.{EnabledUserDirectives, RestJsonClient, StandardUserInfoDirectives}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

object UserApiService {
  val remoteGetKeyPath = FireCloudConfig.Thurloe.authPrefix + FireCloudConfig.Thurloe.get
  val remoteGetKeyURL = FireCloudConfig.Thurloe.baseUrl + remoteGetKeyPath

  val remoteGetAllPath = FireCloudConfig.Thurloe.authPrefix + FireCloudConfig.Thurloe.getAll
  val remoteGetAllURL = FireCloudConfig.Thurloe.baseUrl + remoteGetAllPath

  val remoteGetQueryPath = FireCloudConfig.Thurloe.authPrefix + FireCloudConfig.Thurloe.getQuery
  val remoteGetQueryURL = FireCloudConfig.Thurloe.baseUrl + remoteGetQueryPath

  val remoteSetKeyPath = FireCloudConfig.Thurloe.authPrefix + FireCloudConfig.Thurloe.setKey
  val remoteSetKeyURL = FireCloudConfig.Thurloe.baseUrl + remoteSetKeyPath

  val remoteDeleteKeyURL = remoteGetKeyURL

  val billingPath = FireCloudConfig.Rawls.authPrefix + "/user/billing"
  val billingUrl = FireCloudConfig.Rawls.baseUrl + billingPath
  def billingProjectUrl(project: String) = billingUrl + "/%s".format(project)

  val billingAccountsPath = FireCloudConfig.Rawls.authPrefix + "/user/billingAccounts"
  val billingAccountsUrl = FireCloudConfig.Rawls.baseUrl + billingAccountsPath

  val samRegisterUserPath = "/register/user"
  val samRegisterUserURL = FireCloudConfig.Sam.baseUrl + samRegisterUserPath

  val samRegisterUserInfoPath = "/register/user/v2/self/info"
  val samRegisterUserInfoURL = FireCloudConfig.Sam.baseUrl + samRegisterUserInfoPath

  val samRegisterUserDiagnosticsPath = "/register/user/v2/self/diagnostics"
  val samRegisterUserDiagnosticsURL = FireCloudConfig.Sam.baseUrl + samRegisterUserDiagnosticsPath

  def samUserProxyGroupPath(email: String) = s"/api/google/user/proxyGroup/$email"
  def samUserProxyGroupURL(email: String) = FireCloudConfig.Sam.baseUrl + samUserProxyGroupPath(email)
}

// TODO: this should use UserInfoDirectives, not StandardUserInfoDirectives. That would require a refactoring
// of how we create service actors, so I'm pushing that work out to later.
trait UserApiService
  extends FireCloudRequestBuilding
    with FireCloudDirectives
    with StandardUserInfoDirectives
    with EnabledUserDirectives
    with RestJsonClient {

  implicit val executionContext: ExecutionContext

  lazy val log = LoggerFactory.getLogger(getClass)

  val userServiceConstructor: (UserInfo) => UserService

  val userServiceRoutes =
    path("me") {
      parameter("userDetailsOnly".?) { userDetailsOnly =>
        get { requestContext =>

          // inspect headers for a pre-existing Authorization: header
          val authorizationHeader: Option[HttpCredentials] = (requestContext.request.headers collect {
            case Authorization(h) => h
          }).headOption

          authorizationHeader match {
            // no Authorization header; the user must be unauthorized
            case None =>
              respondWithErrorReport(Unauthorized, "No authorization header in request.", requestContext)
            // browser sent Authorization header; try to query Sam for user status
            case Some(header) =>

              val version1 = !userDetailsOnly.exists(_.equalsIgnoreCase("true"))

              userAuthedRequest(Get(UserApiService.samRegisterUserInfoURL))(AccessToken(header.token())).flatMap { response =>
                handleSamResponse(response, requestContext, version1)
              } recoverWith {
                // we couldn't reach Sam (within timeout period). Respond with a Service Unavailable error.
                case error: Throwable => respondWithErrorReport(ServiceUnavailable, "Identity service did not produce a timely response, please try again later.", error, requestContext)
              }
          }
        }
      }
    } ~
    pathPrefix("api") {
      path("profile" / "billingAccounts") {
        get {
          passthrough(UserApiService.billingAccountsUrl, HttpMethods.GET)
        }
      } ~
      path("profile" / "importstatus") {
        get {
          requireUserInfo() { userInfo =>
            complete { userServiceConstructor(userInfo).importPermission() }
          }
        }
      } ~
      path("profile" / "terra") {
        requireUserInfo() { userInfo =>
          requireEnabledUser(userInfo) {
            get {
              complete { userServiceConstructor(userInfo).getTerraPreference }
            } ~
            post {
              complete { userServiceConstructor(userInfo).setTerraPreference() }
            } ~
            delete {
              complete { userServiceConstructor(userInfo).deleteTerraPreference() }
            }
          }
        }
      } ~
      pathPrefix("proxyGroup") {
        path(Segment) { email =>
          passthrough(UserApiService.samUserProxyGroupURL(email), HttpMethods.GET)
        }
      }
    } ~
    pathPrefix("register") {
      pathEnd {
        get {
          passthrough(UserApiService.samRegisterUserURL, HttpMethods.GET)
        }
      } ~
      path("userinfo") {
        requireUserInfo() { userInfo =>
          complete { userServiceConstructor(userInfo).getUserProfileGoogle }
        }
      } ~
      pathPrefix("profile") {
        // GET /profile - get all keys for current user
        pathEnd {
          get {
            requireUserInfo() { userInfo =>
              complete {
                userServiceConstructor(userInfo).getAllUserKeys
              }
            }
          }
        }
      }
    }

  private def respondWithErrorReport(statusCode: StatusCode, message: String, requestContext: RequestContext): Future[RouteResult] = {
    requestContext.complete(statusCode, ErrorReport(statusCode=statusCode, message=message))
  }

  private def respondWithErrorReport(statusCode: StatusCode, message: String, error: Throwable, requestContext: RequestContext): Future[RouteResult] = {
    requestContext.complete(statusCode, ErrorReport(statusCode = statusCode, message = message, throwable = error))
  }

  private def handleSamResponse(response: HttpResponse, requestContext: RequestContext, version1: Boolean): Future[RouteResult] = {
    response.status match {
      // Sam rejected our request. User is either invalid or their token timed out; this is truly unauthorized
      case Unauthorized =>
        respondWithErrorReport(Unauthorized, "Request rejected by identity service - invalid user or expired token.", requestContext)
      // Sam 404 means the user is not registered with FireCloud
      case NotFound =>
        respondWithErrorReport(NotFound, "FireCloud user registration not found.", requestContext)
      // Sam error? boo. All we can do is respond with an error.
      case InternalServerError =>
        respondWithErrorReport(InternalServerError, "Identity service encountered an unknown error, please try again.", requestContext)
      // Sam found the user; we'll try to parse the response and inspect it
      case OK =>
        Unmarshal(response).to[RegistrationInfoV2].flatMap { regInfo =>
          handleOkResponse(regInfo, requestContext, version1)
        } recoverWith {
          case error: Throwable => respondWithErrorReport(InternalServerError, "Received unparseable response from identity service.", requestContext)
        }
      case x =>
        // if we get any other error from Sam, pass that error on
        respondWithErrorReport(x.intValue, "Unexpected response validating registration: " + x.toString, requestContext)
    }
  }

  private def handleOkResponse(regInfo: RegistrationInfoV2, requestContext: RequestContext, version1: Boolean): Future[RouteResult] = {
    if (regInfo.enabled) {
      if (version1) {
        respondWithUserDiagnostics(regInfo, requestContext)
      } else {
        requestContext.complete(OK, regInfo)
      }
    } else {
      respondWithErrorReport(Forbidden, "FireCloud user not activated.", requestContext)
    }
  }

  private def respondWithUserDiagnostics(regInfo: RegistrationInfoV2, requestContext: RequestContext): Future[RouteResult] = {
    val authorizationHeader: HttpCredentials = (requestContext.request.headers collect {
      case Authorization(h) => h
    }).head //if we've gotten here, the header already exists. Will instead pass it through since that's "safer", TODO

    userAuthedRequest(Get(UserApiService.samRegisterUserDiagnosticsURL))(AccessToken(authorizationHeader.token())).flatMap { response =>
      response.status match {
        case InternalServerError =>
          respondWithErrorReport(InternalServerError, "Identity service encountered an unknown error, please try again.", requestContext)
        case OK =>
          Unmarshal(response).to[WorkbenchEnabledV2].flatMap { diagnostics =>
            if (diagnostics.inAllUsersGroup && diagnostics.inGoogleProxyGroup) {
              val v1RegInfo = RegistrationInfo(WorkbenchUserInfo(regInfo.userSubjectId, regInfo.userEmail), WorkbenchEnabled(diagnostics.inGoogleProxyGroup, diagnostics.enabled, diagnostics.inAllUsersGroup))
              requestContext.complete(OK, v1RegInfo)
            } else {
              respondWithErrorReport(Forbidden, "FireCloud user not activated.", requestContext)
            }
          }
        case x =>
          respondWithErrorReport(x.intValue, "Unexpected response validating registration: " + x.toString, requestContext)
      }
    }
  }
}
