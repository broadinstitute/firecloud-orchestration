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

  val billingAccountsPath = FireCloudConfig.Rawls.authPrefix + "/user/billingAccounts"
  val billingAccountsUrl = FireCloudConfig.Rawls.baseUrl + billingAccountsPath

  val samRegisterUserPath = "/register/user"
  val samRegisterUserURL = FireCloudConfig.Sam.baseUrl + samRegisterUserPath

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
            val samRequest = Get(UserApiService.samRegisterUserURL)
            pipeline(samRequest) onComplete {
              case Success(response: HttpResponse) =>
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
                    val respJson = response.entity.as[RegistrationInfo]
                    respJson match {
                      case Right(regInfo) =>
                        if (regInfo.enabled.google && regInfo.enabled.ldap && regInfo.enabled.allUsersGroup) {
                          // Sam says the user is fully registered and activated!
                          requestContext.complete(OK, regInfo)
                        } else {
                          // Sam knows about the user, but the user isn't activated
                          respondWithErrorReport(Forbidden, "FireCloud user not activated.", requestContext)
                        }
                      // we couldn't parse the Sam response. Respond with an error.
                      case Left(_) =>
                        // TODO: no obvious way to include the JSON parsing error in the ErrorReport. We define `message` below and it does not belong there.
                        respondWithErrorReport(InternalServerError, "Received unparseable response from identity service.", requestContext)
                    }
                  case x =>
                    // if we get any other error from Sam, pass that error on
                    respondWithErrorReport(x.intValue, "Unexpected response validating registration: " + x.toString, requestContext)
                }
              // we couldn't reach Sam (within timeout period). Respond with a Service Unavailable error.
              case Failure(error) =>
                respondWithErrorReport(ServiceUnavailable, "Identity service did not produce a timely response, please try again later.", error, requestContext)
            }
        }
      }
    } ~
    pathPrefix("api") {
      path("profile" / "billing") {
        passthrough(UserApiService.billingUrl, HttpMethods.GET)
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
      path("userinfo") { requestContext =>
        requestContext.complete(HttpGoogleServicesDAO.getUserProfile(requestContext))
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
}
