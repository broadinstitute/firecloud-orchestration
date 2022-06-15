package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.{JWTWrapper, UserInfo}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Created by mbemis on 3/7/17.
  */
class NihServiceSpec extends AnyFlatSpec with Matchers {

  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  val samDao = new MockSamDAO
  val thurloeDao = new MockThurloeDAO
  val googleDao = new MockGoogleServicesDAO
  val shibbolethDao = new MockShibbolethDAO

  // build the service instance we'll use for tests
  val nihService = new NihService(samDao, thurloeDao, googleDao, shibbolethDao)

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
    val privKey = "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDjVxv6FpPDaoVC\nTtIXWB4U0XrYGCYC86dVbci8r9EKOvJqTmEsvSbBlx6RFyr3VtBB2U6WowpHimg1\nl0eJ1cSQKwJ1Zyd+1tjv5TetnjMhgt5aXszu1P4289UL6HwfcEFSXt3LL1a0A4x7\neiJkXnKb0y40IFo56lIneM94gcjuZSlh+j6K/JLTcc+ZlfwTdHTk8asc+/NwNp/J\n3Xf0oD7yXhzAdXbJg5ACvcBk7pfMIhzJPeroK1DgSIG4E5hUr2P/tNuCrZotL3ZB\njyyG3x4OGiGG77Ma/fvqohWwNMs/W349OihQpdWW3E+O9J+NBL77sLxiUMp1lAH7\nkx0vaK5dAgMBAAECggEANxtQbsi2PLI/ZxlQF9SGRimZC3SfEiwZrb2U4RgFJeOw\nC1DAgWXAVUDaafUhtx7WEIAqap1OypSkOglXj/O/V+r1B5xfmIFfyJbZbj5gyoi8\neU9tgZ8jmBJ23BIYtE4zp1HTlYL+E1ig3vV2DLpQMbF5C62j8VH5ZBQGxoE/QXnT\nCDk95oNdwD9fRLIyFkSVPOMKPLi4PwZc0Pr3q8eDanRjMci4q78gDFf4nKWsu+VB\n+wiYq4jzh4qOE1L6cJz6UTpGFppVZbfuUZkPD40UkF15SyYEmNqFuVZ6/Ut8d78V\nwzz3P+UK5o4SKHgcpIHCcQ+AuA7GSe86+g9GKfrSwQKBgQD67Z7ecOaXZzYEzYrL\ndgRfAI06RxcLL0BnszPpJk9+4XXP0QinKBglx50rcMQ58gI9N9EuTLmfoc1bSsQq\nEXOQeYNbaMb4NntRvXRSr4XFiwI7cuRILIcaIRjjODqooX2ZQsjH6ji5NbPdJGw0\nWdteY9hmP+vPBFij++uZ0ApeiQKBgQDn73AAGAC00FpaikWLjz75u2RLMfV1KnSo\nlJ6GWYdDd39d0iExaGBdDr8rIkgPYTs0vw14gP1bw1zLxFh1dNgwgN2uoGutNgN1\njIbfwvpEN2KlD6PmdCKHGGYHgpruxYQ1EFCn1t7moIF8ZgxyFkcDZ2/iPmDBKOJV\nkOCGF4M8NQKBgQCukwDzawLSlOjdII8OjHXwDncy82CR1HbvbpqP+6pU8NDBG4H0\noY1jQ2QSY+rxEXEDXED5AEIoUC6J9BNT3T5UZmXAA75h062qKa+zExBzZgnQiFdP\n60K3KA2jj2woA+pY5UDA7TA3kqgnE38AUP+wxLA6OwB4z2JH/C1mnnmIWQKBgQDk\nAcu+G9qd5nHcri/eGc1UHjdjgNKIA1u52pjZBKxn09LfPdKpyq1o7jVaxxHGJNTa\nbrNkcpIforfYDcbUeTCKxjSoFkakegP+jE6PLRNw+m28TNrYk/TZkE/FNEzxTDVD\nIS3ZQe/RE5sX2w6DHYlkPEyOQrpFSdbvPUSsLfMyvQKBgEZmwhHTxgHLx1ECV8My\n4zRvGxUqAsKooGexM68vJZ/sAubFwoqqBNCdXHumPdlQ3iIdE7+wOc4gTe39Dkkw\nP14sydKEnkOg+QL49wUsuRfmN/Lsrlpv0O9NvHZBgD5rEckgf/gceFgsMgyLxjD+\nO8DgaX9bIiQsMjBjbYR/uIbL\n-----END PRIVATE KEY-----"
    val pubKey = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA41cb+haTw2qFQk7SF1ge\nFNF62BgmAvOnVW3IvK/RCjryak5hLL0mwZcekRcq91bQQdlOlqMKR4poNZdHidXE\nkCsCdWcnftbY7+U3rZ4zIYLeWl7M7tT+NvPVC+h8H3BBUl7dyy9WtAOMe3oiZF5y\nm9MuNCBaOepSJ3jPeIHI7mUpYfo+ivyS03HPmZX8E3R05PGrHPvzcDafyd139KA+\n8l4cwHV2yYOQAr3AZO6XzCIcyT3q6CtQ4EiBuBOYVK9j/7Tbgq2aLS92QY8sht8e\nDhohhu+zGv376qIVsDTLP1t+PTooUKXVltxPjvSfjQS++7C8YlDKdZQB+5MdL2iu\nXQIDAQAB\n-----END PUBLIC KEY-----"
    val mockShibboleth = mock[ShibbolethDAO]
    when(mockShibboleth.getPublicKey()).thenReturn(Future.successful(pubKey))
    val nihServiceMock = new NihService(samDao, thurloeDao, googleDao, mockShibboleth)

    //expires in the year 2052
    val validStr = Jwt.encode(JwtClaim("{\"eraCommonsUsername\": \"firecloud-dev\", \"iat\": 1652937842}").expiresAt(2602002551L), privKey, JwtAlgorithm.RS256)
    val validJwt = JWTWrapper(validStr)

    //expired in June 2022
    val expStr = Jwt.encode(JwtClaim("{\"eraCommonsUsername\": \"firecloud-dev\", \"iat\": 1655232707}").expiresAt(1655232707), privKey, JwtAlgorithm.RS256)
    val expJwt = JWTWrapper(expStr)

    val userToken: UserInfo = UserInfo("dummyToken", thurloeDao.TCGA_AND_TARGET_LINKED)

    val resp1 = Await.result(nihServiceMock.updateNihLinkAndSyncSelf(userToken, validJwt), 3.seconds)
    val resp2 = Await.result(nihServiceMock.updateNihLinkAndSyncSelf(userToken, expJwt), 3.seconds)

    assert(resp1.toString.contains("200 OK"), "Unexpired token should be accepted")
    assert(resp2.toString.contains("400 Bad Request"), "Expired token should be rejected")
    assert(resp2.toString.contains("Failed to decode JWT"), "Expired token should fail at the decode stage")
  }
}
