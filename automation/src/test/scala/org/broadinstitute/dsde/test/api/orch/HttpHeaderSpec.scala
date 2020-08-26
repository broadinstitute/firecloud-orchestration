package org.broadinstitute.dsde.test.api.orch

import akka.http.scaladsl.model.HttpHeader
import org.broadinstitute.dsde.test.OrchConfig.Users
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.service.Orchestration
import org.broadinstitute.dsde.workbench.config.{Credentials, UserPool}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures
import org.broadinstitute.dsde.workbench.service.{Sam, Thurloe}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.tagobjects.Retryable
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers, OptionValues}

class HttpHeaderSpec extends FreeSpec with Matchers with OptionValues {

  // these headers and values should be added by the Apache proxy
  val expectedHeaders: Seq[HttpHeader] = Seq(
    HttpHeader.parse("X-Frame-Options", "SAMEORIGIN"),
    HttpHeader.parse("X-XSS-Protection", "1; mode=block"),
    HttpHeader.parse("X-Content-Type-Options", "nosniff"),
    HttpHeader.parse("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
  ).collect{
    case HttpHeader.ParsingResult.Ok(hdr, errs) if errs.isEmpty => hdr
  }

  "Expected security-related HTTP headers" - {

    "parsed correctly during test suite setup" in {
      expectedHeaders.size shouldBe 4
    }

    def executeHeaderTests(getUrl: String): Unit = {
      expectedHeaders foreach { hdr =>
        s"'${hdr.name()}: ${hdr.value()}' should be returned from the $getUrl endpoint" in {
          val creds = UserPool.userConfig.Students.getRandomCredentials(1).head
          implicit val authToken: AuthToken = creds.makeAuthToken()

          val resp = Orchestration.getRequest(getUrl)
          val actualHeader = resp.headers.find(_.lowercaseName() == hdr.lowercaseName())
          actualHeader.value.lowercaseName() shouldBe hdr.lowercaseName() // we just did a `find` on this, it should always be true
          actualHeader.value.value() shouldBe hdr.value()
        }
      }
    }

    // unauthenticated endpoint
    executeHeaderTests("/status")

    // authenticated + registered endpoint
    executeHeaderTests("/api/profile/terra")

    // authenticated but registration not required endpoint
    executeHeaderTests("/register/profile")

  }

}