package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{ConsentStatus, SubsystemStatus}
import spray.http.Uri
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.httpx.SprayJsonSupport._

import scala.concurrent.{ExecutionContext, Future}

class HttpConsentDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext) extends ConsentDAO with RestJsonClient {

  private val consentUri = Uri(FireCloudConfig.Duos.baseConsentUrl)

  override def status: Future[SubsystemStatus] = {

    val consentStatus = unAuthedRequestToObject[ConsentStatus](Get(consentUri.withPath(Uri.Path("/status"))))
    consentStatus map { consentStatus =>
      SubsystemStatus(true, Some(List(consentStatus.toString)))
    }
  }
}
