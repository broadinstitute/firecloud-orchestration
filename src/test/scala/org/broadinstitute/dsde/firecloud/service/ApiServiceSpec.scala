package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.webservice.NihApiService
import org.scalatest.{FlatSpec, Matchers}
import spray.httpx.SprayJsonSupport
import spray.routing.HttpService
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration._

/**
  * Created by mbemis on 3/1/17.
  */

// common trait to be inherited by API service tests
trait ApiServiceSpec extends FlatSpec with Matchers with HttpService with ScalatestRouteTest with SprayJsonSupport with FireCloudRequestBuilding {
  // increase the timeout for ScalatestRouteTest from the default of 1 second, otherwise
  // intermittent failures occur on requests not completing in time
  implicit val routeTestTimeout = RouteTestTimeout(5.seconds)

  def actorRefFactory = system

  trait ApiServices extends NihApiService {
    val agoraDao: MockAgoraDAO
    val googleDao: MockGoogleServicesDAO
    val duosDao: MockDuosDAO
    val rawlsDao: MockRawlsDAO
    val searchDao: MockSearchDAO
    val thurloeDao: MockThurloeDAO

    def actorRefFactory = system

    val nihServiceConstructor = NihService.constructor(
      new Application(agoraDao, googleDao, duosDao, rawlsDao, searchDao, thurloeDao)
    )_

  }
}