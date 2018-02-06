package org.broadinstitute.dsde.firecloud.service

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO

abstract class MockApplication extends ServiceSpec {

  def actorRefFactory: ActorSystem = system

  val agoraDao:MockAgoraDAO = new MockAgoraDAO
  val googleServicesDao:MockGoogleServicesDAO = new MockGoogleServicesDAO
  val ontologyDao:MockOntologyDAO = new MockOntologyDAO
  val consentDao:MockConsentDAO = new MockConsentDAO
  val rawlsDao:MockRawlsDAO = new MockRawlsDAO
  val samDao:MockSamDAO = new MockSamDAO
  val searchDao:MockSearchDAO = new MockSearchDAO
  val thurloeDao:MockThurloeDAO = new MockThurloeDAO
  val trialDao:MockTrialDAO = new MockTrialDAO

  val app:Application = Application(agoraDao, googleServicesDao, ontologyDao, consentDao, rawlsDao, samDao, searchDao, thurloeDao, trialDao)

}
