package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.utils.TestRequestBuilding
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
trait ApiServiceSpec extends FlatSpec with Matchers with HttpService with ScalatestRouteTest with SprayJsonSupport with TestRequestBuilding {
  // increase the timeout for ScalatestRouteTest from the default of 1 second, otherwise
  // intermittent failures occur on requests not completing in time
  implicit val routeTestTimeout = RouteTestTimeout(5.seconds)

  def actorRefFactory = system

  trait ApiServices extends NihApiService {
    val agoraDao: MockAgoraDAO
    val googleDao: MockGoogleServicesDAO
    val ontologyDao: MockOntologyDAO
    val consentDao: MockConsentDAO
    val rawlsDao: MockRawlsDAO
    val samDao: MockSamDAO
    val searchDao: MockSearchDAO
    val researchPurposeSupport: MockResearchPurposeSupport
    val thurloeDao: MockThurloeDAO
    val logitDao: LogitDAO
    val shareLogDao: MockShareLogDAO

    def actorRefFactory = system

    val nihServiceConstructor = NihService.constructor(
      new Application(agoraDao, googleDao, ontologyDao, consentDao, rawlsDao, samDao, searchDao, researchPurposeSupport, thurloeDao, logitDao, shareLogDao)
    )_

  }

  // lifted from rawls. prefer this to using theSameElementsAs directly, because its functionality depends on whitespace
  def assertSameElements[T](expected: TraversableOnce[T], actual: TraversableOnce[T]): Unit = {
    expected.toTraversable should contain theSameElementsAs actual.toTraversable
  }

}
