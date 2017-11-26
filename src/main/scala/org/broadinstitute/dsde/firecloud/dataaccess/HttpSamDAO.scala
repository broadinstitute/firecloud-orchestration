package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RegistrationInfo, WithAccessToken}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.client.pipelining.{Get, sendReceive}
import spray.httpx.SprayJsonSupport._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 8/21/17.
  */
class HttpSamDAO( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends SamDAO with RestJsonClient {

  override def registerUser(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    authedRequestToObject[RegistrationInfo](Post(samUserRegistrationUrl))
  }

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    authedRequestToObject[RegistrationInfo](Get(samUserRegistrationUrl))
  }

  override def status: Future[SubsystemStatus] = {
    val pipeline = sendReceive
    pipeline(Get(samStatusUrl)) map { response =>
      val ok = response.status.isSuccess
      SubsystemStatus(ok, if (!ok) Option(List(response.entity.asString)) else None)
    }
  }

}
