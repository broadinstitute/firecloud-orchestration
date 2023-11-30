package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.DUOS.{Consent, DuosDataUse}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impDuosConsent
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus

import scala.concurrent.{ExecutionContext, Future}

class HttpConsentDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext, implicit val materializer: Materializer)
  extends ConsentDAO with SprayJsonSupport with RestJsonClient {

  private val consentUri = Uri(FireCloudConfig.Duos.baseConsentUrl)

  override def status: Future[SubsystemStatus] = {
    getStatusFromDropwizardChecks(unAuthedRequest(Get(consentUri.withPath(Uri.Path("/status")))))
  }
}
