package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.{ProfileClientActor, ProfileClient}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.{StatusCode, HttpCredentials}
import spray.http.HttpHeaders.Authorization
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
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

  val rawlsRegisterUserPath = "/register/user"
  val rawlsRegisterUserURL = FireCloudConfig.Rawls.baseUrl + rawlsRegisterUserPath

  def groupPath(group: String): String = FireCloudConfig.Rawls.authPrefix + "/user/group/%s".format(group)
  def groupUrl(group: String): String = FireCloudConfig.Rawls.baseUrl + groupPath(group)

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
            respondWithUserInfo(Unauthorized, Unauthorized.defaultMessage, requestContext)
          // browser sent Authorization header; try to query rawls for user status
          case Some(c) =>
            val pipeline = authHeaders(requestContext) ~> sendReceive
            val extReq = Get(UserService.rawlsRegisterUserURL)
            pipeline(extReq) onComplete {
              case Success(response) =>
                response.status match {
                  // rawls rejected our request. User is either invalid or their token timed out; this is truly unauthorized
                  case Unauthorized => respondWithUserInfo(Unauthorized, Unauthorized.defaultMessage, requestContext)
                  // rawls 404 means the user is not registered with FireCloud
                  case NotFound => respondWithUserInfo(NotFound, "FireCloud user registration not found", requestContext)
                  // rawls error? boo. All we can do is respond with an error.
                  case InternalServerError => respondWithUserInfo(InternalServerError, InternalServerError.defaultMessage, requestContext)
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
                          respondWithUserInfo(Forbidden, "FireCloud user not activated", requestContext)
                        }
                      // we couldn't parse the rawls response. Respond with an error.
                      case Left(error) =>
                        respondWithUserInfo(InternalServerError, InternalServerError.defaultMessage, requestContext)
                    }
                  case x =>
                    // we got an unexpected error code from rawls; pass it on
                    respondWithUserInfo(InternalServerError, "Unexpected response validating registration: " + x.toString, requestContext)
                }
              // we couldn't reach rawls (within timeout period). Respond with an error.
              case Failure(error) =>
                respondWithUserInfo(InternalServerError, InternalServerError.defaultMessage, requestContext)
            }
        }
      }
    } ~
    requireUserInfo() { userInfo =>
    pathPrefix("api") {
      path("profile" / "billing") { requestContext =>
        val extReq = Get(UserService.billingUrl)
        externalHttpPerRequest(requestContext, extReq)
      }
    } ~
    pathPrefix("register") {
      pathEnd {
        get { requestContext =>
          externalHttpPerRequest(requestContext, Get(UserService.rawlsRegisterUserURL))
        }
      } ~
      path("userinfo") { requestContext =>
        val extReq = Get("https://www.googleapis.com/oauth2/v3/userinfo")
        externalHttpPerRequest(requestContext, extReq)
      } ~
      pathPrefix("profile") {
        // GET /profile - get all keys for current user
        pathEnd {
          get { requestContext =>
            val extReq = Get(UserService.remoteGetAllURL.format(userInfo.getUniqueId))
            externalHttpPerRequest(requestContext, extReq)
          } ~
          post {
            entity(as[BasicProfile]) {
              profileData => requestContext =>
                perRequest(requestContext, Props(new ProfileClientActor(requestContext)),
                  ProfileClient.UpdateProfile(userInfo, profileData))
            }
          }
        }
      }
    }
  }

  private def respondWithUserInfo(statusCode: StatusCode, message: String, requestContext: RequestContext) = {
    requestContext.complete(statusCode, ErrorReport(statusCode=statusCode, message=message))
  }

}
