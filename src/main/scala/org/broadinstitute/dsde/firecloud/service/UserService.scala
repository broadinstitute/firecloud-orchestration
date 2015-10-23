package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.model.{ThurloeKeyValue, FireCloudKeyValue}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.{StandardUserInfoDirectives, UserInfoDirectives}
import org.broadinstitute.dsde.firecloud.{EntityClient, FireCloudConfig}
import org.slf4j.LoggerFactory
import spray.http.{HttpResponse, StatusCodes, HttpMethods}
import spray.routing._
import spray.httpx.SprayJsonSupport._

class UserServiceActor extends Actor with UserService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

// TODO: this should use UserInfoDirectives, not StandardUserInfoDirectives. That would require a refactoring
// of how we create service actors, so I'm pushing that work out to later.
trait UserService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)
  lazy val thurloeRoot = FireCloudConfig.Thurloe.authUrl

  val routes = requireUserInfo() { userInfo =>
    pathPrefix("profile") {

      // GET /profile - get all keys for current user
      pathEnd {
        get { requestContext =>
          val extReq = Get( thurloeRoot + FireCloudConfig.Thurloe.getAll.format(userInfo.getUniqueId) )
          externalHttpPerRequest(requestContext, extReq)
        }
        // TODO: POST /profile - upsert entire profile for current user
      } ~
      path (Segment) { key =>
        // GET /profile/${key} - get specified key for current user
        get { requestContext =>
           val extReq = Get( thurloeRoot + FireCloudConfig.Thurloe.get.format(userInfo.getUniqueId, key) )
           externalHttpPerRequest(requestContext, extReq)
        } ~
        // POST /profile/${key} - upsert specified key for current user
        post {
          entity(as[String]) { value => requestContext =>
            val kv = FireCloudKeyValue(Some(key), Some(value))
            val payload = ThurloeKeyValue(Some(userInfo.getUniqueId), Some(kv))
            val extReq = Post(thurloeRoot + FireCloudConfig.Thurloe.setKey, payload)
            externalHttpPerRequest(requestContext, extReq)
          }
        } ~
        // DELETE /profile/${key} - delete specified key for current user
        delete { requestContext =>
          val extReq = Delete( thurloeRoot + FireCloudConfig.Thurloe.delete.format(userInfo.getUniqueId, key) )
          externalHttpPerRequest(requestContext, extReq)
        }
      }
    }
  }
}
