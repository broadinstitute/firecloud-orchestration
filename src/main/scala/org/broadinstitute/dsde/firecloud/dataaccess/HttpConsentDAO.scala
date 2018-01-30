package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.http.{HttpResponse, Uri}

import scala.concurrent.{ExecutionContext, Future}

class HttpConsentDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext) extends ConsentDAO with RestJsonClient {

  private val consentUri = Uri(FireCloudConfig.Duos.baseConsentUrl)


  override def getRestriction(orspId: String): Unit = {
    val consentUrl = FireCloudConfig.Duos.baseConsentUrl + "/api/consent"
    val req = Get(Uri(consentUrl).withQuery(("name", orspId)))
    // authedRequestToObject[DataUseRestriction](req) // 404 or 200, primitive lives in object.dataUse
    None

  }

  override def status: Future[SubsystemStatus] = {
    getStatusFromDropwizardChecks(unAuthedRequest(Get(consentUri.withPath(Uri.Path("/status")))))
  }
}
