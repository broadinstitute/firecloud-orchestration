package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{LinkedEraAccount, UserInfo, WithAccessToken}

import scala.concurrent.Future

class DisabledExternalCredsDAO extends ExternalCredsDAO with LazyLogging {

  override def getLinkedAccount(userInfo: UserInfo): Future[Option[LinkedEraAccount]] = Future.successful {
    logger.warn("Getting Linked eRA Account from ECM, but ECM is disabled.")
    None
  }

  override def putLinkedEraAccount(linkedEraAccount: LinkedEraAccount, orchInfo: WithAccessToken): Future[Unit] =
    Future.successful {
      logger.warn("Putting Linked eRA Account to ECM, but ECM is disabled.")
    }

  override def deleteLinkedEraAccount(userInfo: UserInfo, orchInfo: WithAccessToken): Future[Unit] =
    Future.successful {
      logger.warn("Deleting Linked eRA Account from ECM, but ECM is disabled.")
    }

  override def getLinkedEraAccountForUsername(username: String,
                                              orchInfo: WithAccessToken
  ): Future[Option[LinkedEraAccount]] = Future.successful {
    logger.warn("Getting Linked eRA Account for username from ECM, but ECM is disabled.")
    None
  }

  override def getActiveLinkedEraAccounts(orchInfo: WithAccessToken): Future[Seq[LinkedEraAccount]] =
    Future.successful {
      logger.warn("Getting Active Linked eRA Accounts from ECM, but ECM is disabled.")
      Seq.empty
    }

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
