package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.HttpHeaders.Authorization
import spray.http.StatusCodes._
import spray.http.{HttpCredentials, HttpMethods, StatusCode, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._
import spray.routing._

import scala.util.{Failure, Success}

class UserServiceActor extends Actor with UserService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

object UserService {
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

  val rawlsRegisterUserPath = "/register/user"
  val rawlsRegisterUserURL = FireCloudConfig.Rawls.baseUrl + rawlsRegisterUserPath

  val rawlsGroupBasePath = FireCloudConfig.Rawls.authPrefix + "/groups"
  val rawlsGroupBaseUrl = FireCloudConfig.Rawls.baseUrl + rawlsGroupBasePath

  def rawlsGroupPath(group: String) = rawlsGroupBasePath + "/%s".format(group)
  def rawlsGroupUrl(group: String) = FireCloudConfig.Rawls.baseUrl + rawlsGroupPath(group)

  def rawlsGroupMemberPath(group: String, role: String, email: String) = rawlsGroupPath(group) + "/%s/%s".format(role, email)
  def rawlsGroupMemberUrl(group: String, role: String, email: String) = FireCloudConfig.Rawls.baseUrl + rawlsGroupMemberPath(group, role, email)

}

// TODO: this should use UserInfoDirectives, not StandardUserInfoDirectives. That would require a refactoring
// of how we create service actors, so I'm pushing that work out to later.
trait UserService extends HttpService with PerRequestCreator with FireCloudRequestBuilding with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)

  val routes =
    path("me") {
      get { requestContext =>

        // inspect headers for a pre-existing Authorization: header
        val authorizationHeader: Option[HttpCredentials] = (requestContext.request.headers collect {
          case Authorization(h) => h
        }).headOption

        authorizationHeader match {
          // no Authorization header; the user must be unauthorized
          case None =>
            respondWithErrorReport(Unauthorized, Unauthorized.defaultMessage, requestContext)
          // browser sent Authorization header; try to query rawls for user status
          case Some(c) =>
            val pipeline = authHeaders(requestContext) ~> sendReceive
            val extReq = Get(UserService.rawlsRegisterUserURL)
            pipeline(extReq) onComplete {
              case Success(response) =>
                response.status match {
                  // rawls rejected our request. User is either invalid or their token timed out; this is truly unauthorized
                  case Unauthorized => respondWithErrorReport(Unauthorized, Unauthorized.defaultMessage, requestContext)
                  // rawls 404 means the user is not registered with FireCloud
                  case NotFound => respondWithErrorReport(NotFound, "FireCloud user registration not found", requestContext)
                  // rawls error? boo. All we can do is respond with an error.
                  case InternalServerError => respondWithErrorReport(InternalServerError, InternalServerError.defaultMessage, requestContext)
                  // rawls found the user; we'll try to parse the response and inspect it
                  case OK =>
                    val respJson = response.entity.as[RegistrationInfo]
                    respJson match {
                      case Right(regInfo) =>
                        if (regInfo.enabled.google && regInfo.enabled.ldap) {
                          // rawls says the user is fully registered and activated!
                          requestContext.complete(OK, regInfo)
                        } else {
                          // rawls knows about the user, but the user isn't activated
                          respondWithErrorReport(Forbidden, "FireCloud user not activated", requestContext)
                        }
                      // we couldn't parse the rawls response. Respond with an error.
                      case Left(error) =>
                        respondWithErrorReport(InternalServerError, InternalServerError.defaultMessage, requestContext)
                    }
                  case x =>
                    // if we get any other error from rawls, pass that error on
                    respondWithErrorReport(x.intValue, "Unexpected response validating registration: " + x.toString, requestContext)
                }
              // we couldn't reach rawls (within timeout period). Respond with a Service Unavailable error.
              case Failure(error) =>
                respondWithErrorReport(ServiceUnavailable, ServiceUnavailable.defaultMessage, requestContext)
            }
        }
      }
    } ~
    pathPrefix("api") {
      path("profile" / "billing") {
        passthrough(UserService.billingUrl, HttpMethods.GET)
      } ~
      path("profile" / "billingAccounts") {
        get {
          passthrough(UserService.billingAccountsUrl, HttpMethods.GET)
        }
      } ~
      pathPrefix("groups") {
        pathEnd {
          get {
            passthrough(UserService.rawlsGroupBaseUrl, HttpMethods.GET)
          }
        } ~
        pathPrefix(Segment) { groupName =>
          pathEnd {
            get {
              passthrough(UserService.rawlsGroupUrl(groupName), HttpMethods.GET)
            } ~
            post {
              passthrough(UserService.rawlsGroupUrl(groupName), HttpMethods.POST)
            } ~
            delete {
              passthrough(UserService.rawlsGroupUrl(groupName), HttpMethods.DELETE)
            }
          } ~
          path("requestAccess") {
            post {
              passthrough(UserService.rawlsGroupUrl(groupName) + "/requestAccess", HttpMethods.POST)
            }
          } ~
          path(Segment / Segment) { (role, email) =>
            put {
              passthrough(UserService.rawlsGroupMemberUrl(groupName, role, email), HttpMethods.PUT)
            } ~
            delete {
              passthrough(UserService.rawlsGroupMemberUrl(groupName, role, email), HttpMethods.DELETE)
            }
          }
        }
      }
    } ~
    pathPrefix("register") {
      pathEnd {
        get {
          passthrough(UserService.rawlsRegisterUserURL, HttpMethods.GET)
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
                passthrough(UserService.remoteGetAllURL.format(userInfo.getUniqueId), HttpMethods.GET)
              }
            }
          }
        }
      }
    }

  private def respondWithErrorReport(statusCode: StatusCode, message: String, requestContext: RequestContext) = {
    requestContext.complete(statusCode, ErrorReport(statusCode=statusCode, message=message))
  }
}
