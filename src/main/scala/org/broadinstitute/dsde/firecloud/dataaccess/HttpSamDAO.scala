package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.{RegistrationInfo, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.http.StatusCodes.{NotFound, OK}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 8/21/17.
  */
class HttpSamDAO( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends SamDAO with RestJsonClient {

  override def registerUser(userInfo: UserInfo): Future[Unit] = {
    userAuthedRequest(Post(samUserRegistrationUrl))(userInfo) map { _ => () }
  }

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    authedRequestToObject[RegistrationInfo](Get(samUserRegistrationUrl))
  }

}
