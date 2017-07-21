package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.SubsystemStatus
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.http.{HttpResponse, Uri}

import scala.concurrent.{ExecutionContext, Future}

class HttpConsentDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext) extends ConsentDAO with RestJsonClient {

  private val consentUri = Uri(FireCloudConfig.Duos.baseConsentUrl)

  override def status: Future[SubsystemStatus] = {
    getStatusFromDropwizardChecks(unAuthedRequest(Get(consentUri.withPath(Uri.Path("/status")))))
  }
}
