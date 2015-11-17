package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.ProfileActor
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.httpx.SprayJsonSupport._
import spray.routing._

class UserServiceActor extends Actor with UserService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

object UserService {
  val remoteGetAllPath = FireCloudConfig.Thurloe.authPrefix + FireCloudConfig.Thurloe.getAll
  val remoteGetAllURL = FireCloudConfig.Thurloe.baseUrl + remoteGetAllPath

  val remoteGetKeyPath = FireCloudConfig.Thurloe.authPrefix + FireCloudConfig.Thurloe.get
  val remoteGetKeyURL = FireCloudConfig.Thurloe.baseUrl + remoteGetKeyPath

  val remoteSetKeyPath = FireCloudConfig.Thurloe.authPrefix + FireCloudConfig.Thurloe.setKey
  val remoteSetKeyURL = FireCloudConfig.Thurloe.baseUrl + remoteSetKeyPath

  val remoteDeleteKeyURL = remoteGetKeyURL

  val billingPath = FireCloudConfig.Rawls.authPrefix + "/user/billing"
  val billingUrl = FireCloudConfig.Rawls.baseUrl + billingPath

}

// TODO: this should use UserInfoDirectives, not StandardUserInfoDirectives. That would require a refactoring
// of how we create service actors, so I'm pushing that work out to later.
trait UserService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)

  val routes = requireUserInfo() { userInfo =>
    pathPrefix("api") {
      path("profile" / "billing") { requestContext =>
        val extReq = Get(UserService.billingUrl)
        externalHttpPerRequest(requestContext, extReq)
      }
    } ~
    pathPrefix("register") {
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
              entity(as[Profile]) {
                profileData => requestContext =>
                  perRequest(requestContext, Props(new ProfileActor(requestContext)),
                    ProfileActor.UpdateProfile(userInfo, profileData))
              }
            }
        } ~
        path(Segment) { key =>
          // GET /profile/${key} - get specified key for current user
          get { requestContext =>
            val extReq = Get(UserService.remoteGetKeyURL.format(userInfo.getUniqueId, key))
            externalHttpPerRequest(requestContext, extReq)
          } ~
            // POST /profile/${key} - upsert specified key for current user
            post {
              entity(as[String]) { value => requestContext =>
                val kv = FireCloudKeyValue(Some(key), Some(value))
                val payload = ThurloeKeyValue(Some(userInfo.getUniqueId), Some(kv))
                val extReq = Post(UserService.remoteSetKeyURL, payload)
                externalHttpPerRequest(requestContext, extReq)
              }
            } ~
            // DELETE /profile/${key} - delete specified key for current user
            delete { requestContext =>
              val extReq = Delete(UserService.remoteDeleteKeyURL.format(userInfo.getUniqueId, key))
              externalHttpPerRequest(requestContext, extReq)
            }
        }
      }
    }
  }
}
