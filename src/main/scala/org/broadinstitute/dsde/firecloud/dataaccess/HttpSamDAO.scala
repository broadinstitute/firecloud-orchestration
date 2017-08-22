package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 8/21/17.
  */
class HttpSamDAO( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends SamDAO with RestJsonClient {

  override def registerUser(userInfo: UserInfo): Future[Unit] = {
    userAuthedRequest(Post(samUserRegistrationUrl))(userInfo) map { _ => () }
  }

}
