package org.broadinstitute.dsde.test.api.orch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers._
import akka.stream.ActorMaterializer
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.UserPool
import org.broadinstitute.dsde.workbench.service.{Orchestration, RestException}
import org.broadinstitute.dsde.workbench.service.Orchestration.storage.ObjectMetadata
import org.scalatest.{FreeSpec, Matchers}

import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.concurrent.duration._

/*
  This test suite relies on a set of static, preconfigured fixtures that exist in the gs://fixtures-for-tests
  bucket (in the broad-dsde-qa project). Those fixtures have permissions set on them that mirror how FireCloud
  applies permissions to workspace buckets - i.e. their permissions use proxy groups.

  If the static fixtures change, these tests are likely to fail.

  If the set of test-suite users - specifically the potter-family students - change and the permissions on the
  fixtures are not updated, these tests are likely to fail. Failures may be intermittent.
 */

class StorageApiSpec extends FreeSpec with Matchers {
  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  case class StorageObject(bucketName: String, objectKey: String)

  val publicUrl = StorageObject("fixtures-for-tests", "public/small-text-file.txt")
  val largeFile = StorageObject("fixtures-for-tests", "student-and-SA/ninemegabytes.file")
  val pngFile   = StorageObject("fixtures-for-tests", "public/broad_logo.png")
  val overriddenContentType = StorageObject("fixtures-for-tests", "public/content-type-override.txt")
  val nonExistent = StorageObject("fixtures-for-tests", "thisdoesntexist/now/or/inthefuture.nope")
  val noAccess = StorageObject("fixtures-for-tests", "no-access/noaccess-text-file.txt")
  val smallText = StorageObject("fixtures-for-tests", "student-and-SA/small-text-file.txt")
  val noSAPermissions = StorageObject("fixtures-for-tests", "student-only/ninemegabytes.file")

  // NB: for requests to nonexistent objects, Google returns a 403 unless the user ALSO has permission to
  // list the contents of the associated bucket, in which case Google returns a 404. In this suite, we do not
  // attempt to test the various error codes that Google is responsible for; we simply want to test that when
  // we get an error code back from Google, we propagate it to the user. Therefore, we have tests here that
  // check for a 403 when requesting non-existent objects, which may seem wrong at first glance to anyone
  // reading this code.

  "metadata endpoint" - {

    "should return metadata response for a public object" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val result:ObjectMetadata = Orchestration.storage.getObjectMetadata(publicUrl.bucketName, publicUrl.objectKey)
      assertResult(publicUrl.bucketName) { result.bucket }
      assertResult(publicUrl.objectKey) { result.name }
    }

    "should return metadata response for an object I have permissions to" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val result:ObjectMetadata = Orchestration.storage.getObjectMetadata(smallText.bucketName, smallText.objectKey)
      assertResult(smallText.bucketName) { result.bucket }
      assertResult(smallText.objectKey) { result.name }
    }

    "should return 403 for a non-existent object" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val requestEx = intercept[RestException] {
        Orchestration.storage.getObjectMetadata(nonExistent.bucketName, nonExistent.objectKey)
      }
      assert(requestEx.getMessage.toLowerCase.contains("forbidden"))
    }

    "should return 403 for a file I don't have permissions to" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val requestEx = intercept[RestException] {
        Orchestration.storage.getObjectMetadata(noAccess.bucketName, noAccess.objectKey)
      }
      assert(requestEx.getMessage.toLowerCase.contains("forbidden"))
    }

  }

  "cookie-authed download endpoint" - {

    "should return 403 for a non-existent object" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val response = Orchestration.storage.getObjectDownload(nonExistent.bucketName, nonExistent.objectKey)
      assertResult(StatusCodes.Forbidden) { response.status }
    }

    "should return 403 for a file I don't have permissions to" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val response = Orchestration.storage.getObjectDownload(noAccess.bucketName, noAccess.objectKey)
      assertResult(StatusCodes.Forbidden) { response.status }
    }

    "should return OK for small (<8MB) public files" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val response:HttpResponse = Orchestration.storage.getObjectDownload(publicUrl.bucketName, publicUrl.objectKey)

      assertResult(StatusCodes.OK) { response.status }

      val contentLength:Long = response.header[`Content-Length`].map(_.length).getOrElse(-1)
      assert(contentLength < 8 * 1024 * 1024, s"content-length should be under 8MB; was $contentLength" )
    }

    "should return content directly for small (<8MB) files" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val response:HttpResponse = Orchestration.storage.getObjectDownload(smallText.bucketName, smallText.objectKey)

      assertResult(StatusCodes.OK) { response.status }

      val contentLength:Long = response.header[`Content-Length`].map(_.length).getOrElse(-1)
      assert(contentLength < 8 * 1024 * 1024, s"content-length should be under 8MB; was $contentLength" )

      val responseString: String = Await.result(response.entity.toStrict(2.minutes).map(_.data.utf8String), 2.minutes)
      assertResult("this is a small text file.") { responseString }
    }

    "should redirect to a signed url for large (>8MB) files" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val response:HttpResponse = Orchestration.storage.getObjectDownload(largeFile.bucketName, largeFile.objectKey)

      assertResult(StatusCodes.TemporaryRedirect) { response.status }

      val redirectUriOption = response.header[Location].map(_.getUri())
      assert(redirectUriOption.isDefined, "redirect should have a Location header")
      redirectUriOption.map { redirectUri =>
        assert(redirectUri.query().toMultiMap.containsKey("GoogleAccessId"), "signed url should have GoogleAccessId query param")
        assert(redirectUri.query().toMultiMap.containsKey("Expires"), "signed url should have Expires query param")
        assert(redirectUri.query().toMultiMap.containsKey("Signature"), "signed url should have Signature query param")

        assert(redirectUri.path().contains(largeFile.bucketName), "signed url should contain bucket")
        assert(redirectUri.path().contains(largeFile.objectKey), "signed url should contain object key")
      }
    }

    "should redirect to a direct-download url when SA doesn't have signing permissions for large (>8MB) files " in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val response:HttpResponse = Orchestration.storage.getObjectDownload(noSAPermissions.bucketName, noSAPermissions.objectKey)

      assertResult(StatusCodes.TemporaryRedirect) { response.status }

      val redirectUriOption = response.header[Location].map(_.getUri())
      assert(redirectUriOption.isDefined, "redirect should have a Location header")
      redirectUriOption.map { redirectUri =>
        val expected = s"https://storage.cloud.google.com/${noSAPermissions.bucketName}/${noSAPermissions.objectKey}"
        assertResult(expected) { redirectUri.toString }
      }
    }

    "should propagate inferred content-type" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val response:HttpResponse = Orchestration.storage.getObjectDownload(pngFile.bucketName, pngFile.objectKey)

      assertResult(StatusCodes.OK) { response.status }

      val contentType = response.header[`Content-Type`].map(_.contentType.toString())

      assertResult(Some("image/png"),
        "content type for png should be inferred and returned") {
        contentType
      }
    }

    "should propagate overriden content-type" in {
      implicit val authToken: AuthToken = UserPool.chooseStudent.makeAuthToken()
      val response:HttpResponse = Orchestration.storage.getObjectDownload(overriddenContentType.bucketName, overriddenContentType.objectKey)

      assertResult(StatusCodes.OK) { response.status }

      val contentType = response.header[`Content-Type`].map(_.contentType.toString())

      assertResult(Some("application/json"),
        "content type for png should be inferred and returned") {
        contentType
      }
    }

  }
}