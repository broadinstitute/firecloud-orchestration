package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.DUOS.{Consent, DuosDataUse}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impDuosConsent
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.http.Uri
import spray.httpx.SprayJsonSupport._

import scala.concurrent.{ExecutionContext, Future}

class HttpConsentDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends ConsentDAO with RestJsonClient {

  private val consentUri = Uri(FireCloudConfig.Duos.baseConsentUrl)

  override def getRestriction(orspId: String)(implicit userInfo: WithAccessToken): Future[Option[DuosDataUse]] = {
    val consentUrl = FireCloudConfig.Duos.baseConsentUrl + "/api/consent"
    val req = Get(Uri(consentUrl).withQuery(("name", orspId)))
    authedRequestToObject[Consent](req) map (_.dataUse)
  }

  override def status: Future[SubsystemStatus] = {
    getStatusFromDropwizardChecks(unAuthedRequest(Get(consentUri.withPath(Uri.Path("/status")))))
  }
}
