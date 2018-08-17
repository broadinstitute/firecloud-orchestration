package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.UserService.ImportPermission
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.HttpHeaders.Authorization
import spray.http.StatusCodes._
import spray.http.{HttpCredentials, HttpMethods, HttpResponse, StatusCode}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._
import spray.routing._

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

  val samRegisterUserV2Path = "/register/user/v2/self/info"
  val samRegisterUserV2URL = FireCloudConfig.Sam.baseUrl + samRegisterUserV2Path

  val rawlsGroupBasePath = FireCloudConfig.Rawls.authPrefix + "/groups"
  val rawlsGroupBaseUrl = FireCloudConfig.Rawls.baseUrl + rawlsGroupBasePath

  def rawlsGroupPath(group: String) = rawlsGroupBasePath + "/%s".format(group)
  def rawlsGroupUrl(group: String) = FireCloudConfig.Rawls.baseUrl + rawlsGroupPath(group)

  def rawlsGroupMemberPath(group: String, role: String, email: String) = rawlsGroupPath(group) + "/%s/%s".format(role, email)
  def rawlsGroupMemberUrl(group: String, role: String, email: String) = FireCloudConfig.Rawls.baseUrl + rawlsGroupMemberPath(group, role, email)

  def rawlsGroupRequestAccessPath(group: String) = rawlsGroupPath(group) + "/requestAccess"
  def rawlsGroupRequestAccessUrl(group: String) = FireCloudConfig.Rawls.baseUrl + rawlsGroupRequestAccessPath(group)

  def samUserProxyGroupPath(email: String) = s"/api/google/user/proxyGroup/$email"
  def samUserProxyGroupURL(email: String) = FireCloudConfig.Sam.baseUrl + samUserProxyGroupPath(email)
}

// TODO: this should use UserInfoDirectives, not StandardUserInfoDirectives. That would require a refactoring
// of how we create service actors, so I'm pushing that work out to later.
trait UserApiService extends HttpService with PerRequestCreator with FireCloudRequestBuilding with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)

  val trialServiceConstructor: () => TrialService
  val userServiceConstructor: (WithAccessToken) => UserService

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
            case Some(_) =>
              val pipeline = authHeaders(requestContext) ~> sendReceive
              val version2 = userDetailsOnly.exists(_.equalsIgnoreCase("true"))
              val samRequest = if (version2) Get(UserApiService.samRegisterUserV2URL) else Get(UserApiService.samRegisterUserURL)
              pipeline(samRequest) onComplete {
                case Success(response: HttpResponse) =>
                  handleSamResponse(response, requestContext, version2)
                // we couldn't reach Sam (within timeout period). Respond with a Service Unavailable error.
                case Failure(error) =>
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
          requireUserInfo() { userInfo => requestContext =>
            perRequest(requestContext, UserService.props(userServiceConstructor, userInfo), ImportPermission)
          }
        }
      } ~
      pathPrefix("profile" / "trial") {
        pathEnd {
          post {
            parameter("operation" ? "enroll") { op =>
              requireUserInfo() { userInfo => requestContext =>
                val operation = op.toLowerCase match {
                  case "enroll" => Some(TrialService.EnrollUser(userInfo))
                  case "finalize" => Some(TrialService.FinalizeUser(userInfo))
                  case _ => None
                }

                if (operation.nonEmpty)
                  perRequest(requestContext, TrialService.props(trialServiceConstructor), operation.get)
                else
                  requestContext.complete(BadRequest, ErrorReport(s"Invalid operation '$op'"))
              }
            }
          }
        } ~
        path("userAgreement") {
          put {
            requireUserInfo() { userInfo => requestContext =>
              perRequest(requestContext,
                TrialService.props(trialServiceConstructor),
                TrialService.RecordUserAgreement(userInfo))
            }
          }
        }
      } ~
      pathPrefix("groups") {
        pathEnd {
          get {
            passthrough(UserApiService.rawlsGroupBaseUrl, HttpMethods.GET)
          }
        } ~
        pathPrefix(Segment) { groupName =>
          pathEnd {
            get {
              passthrough(UserApiService.rawlsGroupUrl(groupName), HttpMethods.GET)
            } ~
            post {
              passthrough(UserApiService.rawlsGroupUrl(groupName), HttpMethods.POST)
            } ~
            delete {
              passthrough(UserApiService.rawlsGroupUrl(groupName), HttpMethods.DELETE)
            }
          } ~
          path("requestAccess") {
            post {
              passthrough(UserApiService.rawlsGroupRequestAccessUrl(groupName), HttpMethods.POST)
            }
          } ~
          path(Segment / Segment) { (role, email) =>
            put {
              passthrough(UserApiService.rawlsGroupMemberUrl(groupName, role, email), HttpMethods.PUT)
            } ~
            delete {
              passthrough(UserApiService.rawlsGroupMemberUrl(groupName, role, email), HttpMethods.DELETE)
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
        requireUserInfo() { userInfo => requestContext =>
          requestContext.complete(HttpGoogleServicesDAO.getUserProfile(userInfo))
        }
      } ~
      pathPrefix("profile") {
        // GET /profile - get all keys for current user
        pathEnd {
          get {
            requireUserInfo() { userInfo =>
              mapRequest(addFireCloudCredentials) {
                passthrough(UserApiService.remoteGetAllURL.format(userInfo.getUniqueId), HttpMethods.GET)
              }
            }
          }
        }
      }
    }

  private def respondWithErrorReport(statusCode: StatusCode, message: String, requestContext: RequestContext): Unit = {
    requestContext.complete(statusCode, ErrorReport(statusCode=statusCode, message=message))
  }

  private def respondWithErrorReport(statusCode: StatusCode, message: String, error: Throwable, requestContext: RequestContext): Unit = {
    requestContext.complete(statusCode, ErrorReport(statusCode = statusCode, message = message, throwable = error))
  }

  private def handleSamResponse(response: HttpResponse, requestContext: RequestContext, version2: Boolean): Unit = {
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
        val respJson: Either[Deserialized[RegistrationInfoV2], Deserialized[RegistrationInfo]] =
          if (version2) Left(response.entity.as[RegistrationInfoV2]) else Right(response.entity.as[RegistrationInfo])
        handleOkResponse(respJson, requestContext, version2)
      case x =>
        // if we get any other error from Sam, pass that error on
        respondWithErrorReport(x.intValue, "Unexpected response validating registration: " + x.toString, requestContext)
    }
  }

  private def handleOkResponse(respJson: Either[Deserialized[RegistrationInfoV2], Deserialized[RegistrationInfo]], requestContext: RequestContext, version2: Boolean): Unit = {
    respJson match {
      // RegistrationInfo
      case Right(deserializedRegInfo) => deserializedRegInfo match {
        case Right(regInfo) =>
          if (regInfo.enabled.google && regInfo.enabled.ldap && regInfo.enabled.allUsersGroup) {
            requestContext.complete(OK, regInfo)
          } else {
            respondWithErrorReport(Forbidden, "FireCloud user not activated.", requestContext)
          }
        case Left(_) =>
          respondWithErrorReport(InternalServerError, "Received unparseable response from identity service.", requestContext)
      }

      // RegistrationInfoV2
      case Left(deserializedRegInfo) => deserializedRegInfo match {
        case Right(regInfo) =>
          if (regInfo.enabled) {
            requestContext.complete(OK,regInfo)
          } else {
            respondWithErrorReport(Forbidden, "FireCloud user not activated.", requestContext)
          }
        case Left(_) =>
          respondWithErrorReport(InternalServerError, "Received unparseable response from identity service.", requestContext)
      }
    }
  }
}
