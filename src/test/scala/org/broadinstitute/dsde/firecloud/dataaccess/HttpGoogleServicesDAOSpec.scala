package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class HttpGoogleServicesDAOSpec extends FlatSpec with Matchers {

  "HttpGoogleServicesDAO" should "fetch the current price list" in {
    implicit val system = ActorSystem()
    import system.dispatcher

    val priceList: GooglePriceList = Await.result(HttpGoogleServicesDAO.fetchPriceList, Duration.Inf)

    priceList.version should startWith ("v")
    priceList.updated should not be empty
    priceList.prices.cpBigstoreStorage.us should be > BigDecimal(0)
    priceList.prices.cpComputeengineInternetEgressAPAC.tiers.size should be > 0
    priceList.prices.cpComputeengineInternetEgressAU.tiers.size should be > 0
    priceList.prices.cpComputeengineInternetEgressCN.tiers.size should be > 0
    priceList.prices.cpComputeengineInternetEgressNA.tiers.size should be > 0
  }
}
