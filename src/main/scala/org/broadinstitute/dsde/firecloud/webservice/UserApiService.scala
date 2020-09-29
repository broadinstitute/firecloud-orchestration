package org.broadinstitute.dsde.firecloud.webservice

import akka.http.javadsl.model.HttpResponse
import akka.http.scaladsl.model.HttpMethods
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{DsdeHttpDAO, HttpGoogleServicesDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{RequestContext, RouteResult}
import akka.http.scaladsl.model.{HttpMethods, StatusCode}
import akka.http.scaladsl.model.headers.{Authorization, HttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.unmarshalling.Unmarshal

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

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
trait UserApiService extends FireCloudRequestBuilding with FireCloudDirectives with StandardUserInfoDirectives with DsdeHttpDAO {

  implicit val executionContext: ExecutionContext

  lazy val log = LoggerFactory.getLogger(getClass)

  val trialServiceConstructor: () => TrialService
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

              executeRequestRaw(OAuth2BearerToken(header.token()))(Get(UserApiService.samRegisterUserInfoURL)) flatMap { response =>
                handleSamResponse(response, requestContext, version1)
              } recover {
                case error: Throwable =>
                // we couldn't reach Sam (within timeout period). Respond with a Service Unavailable error.
                respondWithErrorReport(ServiceUnavailable, "Identity service did not produce a timely response, please try again later.", error, requestContext)
              }
          }
        }
      }
    } ~
    pathPrefix("api") {
      pathPrefix("profile" / "billing") {
        pathEnd {
          get {
            passthrough(UserApiService.billingUrl, HttpMethods.GET)
          }
        } ~
        path(Segment) { projectName =>
          get {
            passthrough(UserApiService.billingProjectUrl(projectName), HttpMethods.GET)
          }
        }
      } ~
      path("profile" / "billingAccounts") {
        get {
          passthrough(UserApiService.billingAccountsUrl, HttpMethods.GET)
        }
      } ~
      path("profile" / "importstatus") {
        get {
          requireUserInfo() { userInfo =>
            complete { userServiceConstructor(userInfo).ImportPermission }
          }
        }
      } ~
      path("profile" / "terra") {
        get {
          requireUserInfo() { userInfo =>
            complete { userServiceConstructor(userInfo).GetTerraPreference }
          }
        } ~
        post {
          requireUserInfo() { userInfo =>
            complete { userServiceConstructor(userInfo).SetTerraPreference }
          }
        } ~
        delete {
          requireUserInfo() { userInfo =>
            complete { userServiceConstructor(userInfo).DeleteTerraPreference }
          }
        }
      } ~
      pathPrefix("profile" / "trial") {
        pathEnd {
          post {
            parameter("operation") { op =>
              requireUserInfo() { userInfo =>
                op.toLowerCase match {
                  case "finalize" => complete { trialServiceConstructor().FinalizeUser(userInfo) }
                  case _ => complete { BadRequest }
                }
              }
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
          complete { HttpGoogleServicesDAO.getUserProfile(userInfo) }
        }
      } ~
      pathPrefix("profile") {
        // GET /profile - get all keys for current user
        pathEnd {
          get {
            requireUserInfo() { userInfo =>
              complete { userServiceConstructor(userInfo).GetAllUserKeys }
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

  private def handleSamResponse(response: HttpResponse, requestContext: RequestContext, version1: Boolean): Unit = {
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
        val respJson: Deserialized[RegistrationInfoV2] = responseAs[RegistrationInfoV2]
        handleOkResponse(respJson, requestContext, version1)
      case x =>
        // if we get any other error from Sam, pass that error on
        respondWithErrorReport(x.intValue, "Unexpected response validating registration: " + x.toString, requestContext)
    }
  }

  private def handleOkResponse(respJson: Deserialized[RegistrationInfoV2], requestContext: RequestContext, version1: Boolean): Unit = {
    respJson match {
      case Right(regInfo) =>
        if (regInfo.enabled) {
          if (version1) {
            respondWithUserDiagnostics(regInfo, requestContext)
          } else {
            requestContext.complete(OK, regInfo)
          }
        } else {
          respondWithErrorReport(Forbidden, "FireCloud user not activated.", requestContext)
        }
      case Left(_) =>
        respondWithErrorReport(InternalServerError, "Received unparseable response from identity service.", requestContext)
    }
  }

  private def respondWithUserDiagnostics(regInfo: RegistrationInfoV2, requestContext: RequestContext): Unit = {
    val authorizationHeader: HttpCredentials = (requestContext.request.headers collect {
      case Authorization(h) => h
    }).head //if we've gotten here, the header already exists. Will instead pass it through since that's "safer", TODO

    executeRequestRaw(OAuth2BearerToken(authorizationHeader.token()))(Get(UserApiService.samRegisterUserDiagnosticsURL)).map { response =>
      response.status match {
        case InternalServerError =>
          respondWithErrorReport(InternalServerError, "Identity service encountered an unknown error, please try again.", requestContext)
        case OK =>
          Unmarshal(response).to[WorkbenchEnabledV2].map { diagnostics =>
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
