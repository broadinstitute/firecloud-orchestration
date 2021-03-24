package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.freespec.AnyFreeSpec

import scala.concurrent.ExecutionContext

class FireCloudDirectivesSpec extends AnyFreeSpec with ScalatestRouteTest with FireCloudDirectives {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  "FireCloudDirectives" - {
    "Unencoded passthrough URLs" - {
      "should be encoded" in {
        val unencoded = "http://abc.com/path with spaces/"
        val encoded = encodeUri(unencoded)
        assert(encoded.equals("http://abc.com/path%20with%20spaces/"))
      }
    }
    "Encoded passthrough URLs" - {
      "should not be re-encoded" in {
        val unencoded = "https://abc.com/path%20with%20spaces/"
        val encoded = encodeUri(unencoded)
        assert(encoded.equals("https://abc.com/path%20with%20spaces/"))
      }
    }
    "Passthrough URLs with no parameters" - {
      "should not break during encoding" in {
        val unencoded = "http://abc.com/path"
        val encoded = encodeUri(unencoded)
        assert(encoded.equals("http://abc.com/path"))
      }
    }
    "URL with port specified" - {
      "should not break during encoding" in {
        val unencoded = "http://abc.com:8080/"
        val encoded = encodeUri(unencoded)
        assert(encoded.equals("http://abc.com:8080/"))
      }
    }
  }
}
