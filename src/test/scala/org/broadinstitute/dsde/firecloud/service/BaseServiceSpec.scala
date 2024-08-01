package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.server.ExceptionHandler
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.{Application, FireCloudApiService}
import org.scalatest.BeforeAndAfter

class BaseServiceSpec extends ServiceSpec with BeforeAndAfter {

  // this gets fed into sealRoute so that exceptions are handled the same in tests as in real life
  implicit val exceptionHandler: ExceptionHandler = FireCloudApiService.exceptionHandler

  val agoraDao:MockAgoraDAO = new MockAgoraDAO
  val googleServicesDao:MockGoogleServicesDAO = new MockGoogleServicesDAO
  val ontologyDao:MockOntologyDAO = new MockOntologyDAO
  val rawlsDao:MockRawlsDAO = new MockRawlsDAO
  val samDao:MockSamDAO = new MockSamDAO
  val searchDao:MockSearchDAO = new MockSearchDAO
  val researchPurposeSupport:MockResearchPurposeSupport = new MockResearchPurposeSupport
  val thurloeDao:MockThurloeDAO = new MockThurloeDAO
  val shareLogDao:MockShareLogDAO = new MockShareLogDAO
  val shibbolethDao:MockShibbolethDAO = new MockShibbolethDAO
  val cwdsDao:CwdsDAO = new MockCwdsDAO
  val ecmDao:ExternalCredsDAO = new DisabledExternalCredsDAO

  val app:Application =
    new Application(agoraDao, googleServicesDao, ontologyDao, rawlsDao, samDao, searchDao, researchPurposeSupport, thurloeDao, shareLogDao, shibbolethDao, cwdsDao, ecmDao)

}
