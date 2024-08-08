package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.{JWTWrapper, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import java.security.{KeyPairGenerator, PrivateKey}
import java.time.Instant
import java.util.Base64

/**
  * Created by mbemis on 3/7/17.
  */
class NihServiceSpec extends AnyFlatSpec with Matchers {

  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  val samDao = new MockSamDAO
  val thurloeDao = new MockThurloeDAO
  val googleDao = new MockGoogleServicesDAO
  val shibbolethDao = new MockShibbolethDAO
  val ecmDao = new DisabledExternalCredsDAO

  // build the service instance we'll use for tests
  val nihService = new NihService(samDao, thurloeDao, googleDao, shibbolethDao, ecmDao)

  val usernames = Map("fcSubjectId1" -> "nihUsername1", "fcSubjectId2" -> "nihUsername2")

  val expiretimes1 = Map("fcSubjectId1" -> DateUtils.nowMinus24Hours.toString, "fcSubjectId2" -> DateUtils.nowPlus24Hours.toString)
  val currentUsernames1 = Map("fcSubjectId2" -> "nihUsername2")

  val expiretimes2 = Map("fcSubjectId1" -> DateUtils.nowMinus24Hours.toString)
  val expiretimes3 = Map("fcSubjectId1" -> DateUtils.nowMinus24Hours.toString, "fcSubjectId2" -> "not a number")

  "NihService" should "only include unexpired users when handling expired and unexpired users" in {
    assertResult(currentUsernames1) {
      nihService.filterForCurrentUsers(usernames, expiretimes1)
    }
  }

  it should "not include users with no expiration times" in {
    assertResult(Map()) {
      nihService.filterForCurrentUsers(usernames, expiretimes2)
    }
  }

  it should "not include users with unparseable expiration times" in {
    assertResult(Map()) {
      nihService.filterForCurrentUsers(usernames, expiretimes3)
    }
  }

  it should "honor expiration of JWTs" in {
    //Set up a Mock Shibboleth with a public key that matches a private key we have access to
    //The private key that matches the public key in MockShibbolethDao is lost to time
    val keypairGen = KeyPairGenerator.getInstance("RSA")
    keypairGen.initialize(1024)
    val keypair = keypairGen.generateKeyPair()

    val privKey: PrivateKey = keypair.getPrivate
    val pubKey: String = s"-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder.encodeToString(keypair.getPublic.getEncoded)}\n-----END PUBLIC KEY-----"

    val mockShibboleth = mock[ShibbolethDAO]
    when(mockShibboleth.getPublicKey()).thenReturn(Future.successful(pubKey))
    val nihServiceMock = new NihService(samDao, thurloeDao, googleDao, mockShibboleth, ecmDao)

    // expires in 15 minutes
    val expiresInTheFuture: Long = Instant.ofEpochMilli(System.currentTimeMillis() + (15 * 60 * 1000)).getEpochSecond // 15 minutes * 60 seconds * 1000 milliseconds
    val validStr = Jwt.encode(JwtClaim("{\"eraCommonsUsername\": \"firecloud-dev\", \"iat\": 1652937842}").expiresAt(expiresInTheFuture), privKey, JwtAlgorithm.RS256)
    val validJwt = JWTWrapper(validStr)

    // expired 1 minute ago
    val expiresInThePast: Long = Instant.ofEpochMilli(System.currentTimeMillis() - (60 * 1000)).getEpochSecond // 60 seconds * 1000 milliseconds
    val expStr = Jwt.encode(JwtClaim("{\"eraCommonsUsername\": \"firecloud-dev\", \"iat\": 1655232707}").expiresAt(expiresInThePast), privKey, JwtAlgorithm.RS256)
    val expJwt = JWTWrapper(expStr)

    val userToken: UserInfo = UserInfo("dummyToken", thurloeDao.TCGA_AND_TARGET_LINKED)

    val resp1 = Await.result(nihServiceMock.updateNihLinkAndSyncSelf(userToken, validJwt), 3.seconds)
    val resp2 = Await.result(nihServiceMock.updateNihLinkAndSyncSelf(userToken, expJwt), 3.seconds)


    resp1 match {
      case _@ RequestComplete((StatusCodes.OK, _)) => succeed
      case x => fail(s"Unexpired token should be accepted. Response was: $x")
    }

    resp2 match {
      case _@ RequestComplete((StatusCodes.BadRequest, errorReport: ErrorReport)) =>
        errorReport.message shouldBe "Failed to decode JWT"
      case x =>
        fail(s"Expired token should fail at the decode stage. Response was: $x")
    }
  }
}
