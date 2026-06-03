package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.WithAccessToken

import scala.concurrent.Future

class DisabledExternalCredsDAO extends ExternalCredsDAO with LazyLogging {

  override def getVisas(provider: String,
                        userId: String,
                        issuer: String,
                        visaType: String,
                        orchInfo: WithAccessToken
  ): Future[Seq[AnyRef]] =
    Future.successful {
      logger.warn("Getting Visas from ECM, but ECM is disabled.")
      Seq.empty
    }
}
