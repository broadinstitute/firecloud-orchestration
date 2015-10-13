package org.broadinstitute.dsde.firecloud.service

import org.scalatest.FreeSpec
import spray.testkit.ScalatestRouteTest

class FireCloudDirectivesSpec extends FreeSpec with ScalatestRouteTest with FireCloudDirectives {

  def actorRefFactory = system

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
        val unencoded = "http://abc.com/"
        val encoded = encodeUri(unencoded)
        assert(encoded.equals("http://abc.com/"))
      }
    }
  }
}
