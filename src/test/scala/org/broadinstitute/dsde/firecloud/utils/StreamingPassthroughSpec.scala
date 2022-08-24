package org.broadinstitute.dsde.firecloud.utils

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class StreamingPassthroughSpec extends AnyFreeSpec with Matchers with StreamingPassthrough {

  implicit val system: ActorSystem = ActorSystem("StreamingPassthroughSpec")
  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

  "convertToTargetUri" - {
    "should calculate a remainder" in {
      val requestUri = Uri("http://localhost:8123/foo/bar/baz/qux")
      val localBasePath = Path("/foo/bar")
      val remoteBaseUri = Uri("https://example.com/api/version/foo")

      val expected = Uri("https://example.com/api/version/foo/baz/qux")
      convertToRemoteUri(requestUri, localBasePath, remoteBaseUri) shouldBe expected
    }
    "should pass on a querystring" in {
      val requestUri = Uri("http://localhost:8123/foo/bar/baz/qux?hello=world")
      val localBasePath = Path("/foo/bar")
      val remoteBaseUri = Uri("https://example.com/api/version/foo")

      val expected = Uri("https://example.com/api/version/foo/baz/qux?hello=world")
      convertToRemoteUri(requestUri, localBasePath, remoteBaseUri) shouldBe expected
    }
    "should handle an empty remainder" is (pending)
  }

  "should NOT forward Timeout-Access header" is (pending)
  "should forward Authorization header" is (pending)
  "should forward miscellaneous headers" is (pending)
  "should preserve request method" is (pending)

  "should reply with remote-system 2xx responses" is (pending)
  "should reply with remote-system 4xx errors" is (pending)
  "should reply with remote-system 5xx errors" is (pending)

  "mockserver-based route test" is (pending)

}
