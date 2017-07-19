package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{DropwizardHealth, SubsystemStatus}
import spray.http.Uri
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

class HttpConsentDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext) extends ConsentDAO with RestJsonClient {

  private val consentUri = Uri(FireCloudConfig.Duos.baseConsentUrl)

  override def status: Future[SubsystemStatus] = {
    val consentStatus = unAuthedRequestToObject[Map[String, DropwizardHealth]](Get(consentUri.withPath(Uri.Path("/status"))))

    consentStatus map { consentStatus =>
      val ok = consentStatus.values.forall(_.healthy)
      val errors = consentStatus.values.filter { dw =>
        !dw.healthy && dw.message.isDefined
      }.map(_.message.get).toList
      errors match {
        case x if x.isEmpty => SubsystemStatus(ok)
        case _ => SubsystemStatus(ok, Some(errors))
      }
    }
  }
}
