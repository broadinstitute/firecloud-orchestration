package org.broadinstitute.dsde.test.api.orch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, UserPool}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.broadinstitute.dsde.workbench.service.{Orchestration, RestException}
import org.broadinstitute.dsde.workbench.service.Orchestration.storage.ObjectMetadata
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.language.implicitConversions

/*
  This test suite relies on a set of static fixtures that exist in the gs://fixtures-for-tests
  bucket (in the broad-dsde-qa project). Some of these fixtures have permissions preconfigured - for the public-file
  and no-access tests. If the permissions on these fixtures change, these tests will fail.

  The default object ACL for new files in the gs://fixtures-for-tests is set very narrowly, with three individual
  Workbench developers as owners and no other access. This allows tests to ensure they are setting ACLs appropriate
  to the test; no chance of unexpected read access due to default ACLs. If the default ACL for this bucket changes,
  these tests are likely to fail.
 */

class StorageApiSpec extends AnyFreeSpec with StorageApiSpecSupport with Matchers with LazyLogging with Eventually {
  implicit val system: ActorSystem = ActorSystem()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val student: Credentials = UserPool.chooseStudent
  logger.info(s"StorageApiSpec running as student <${student.email}>")

  // implicit conversions to allow shorthand for working with GcsPath/GcsBucketName/GcsObjectName
  implicit def stringToBucketName(s: String): GcsBucketName = GcsBucketName(s)
  implicit def stringToObjectName(s: String): GcsObjectName = GcsObjectName(s)
  implicit def bucketNameToString(b: GcsBucketName): String = b.value
  implicit def objectNameToString(o: GcsObjectName): String = o.value

  val publicUrl = GcsPath("fixtures-for-tests", "fixtures/public/small-text-file.txt")
  val pngFile   = GcsPath("fixtures-for-tests", "fixtures/public/broad_logo.png")
  val overriddenContentType = GcsPath("fixtures-for-tests", "fixtures/public/content-type-override.txt")

  val noAccess = GcsPath("fixtures-for-tests", "fixtures/noaccess/noaccess.txt")
  val nonExistent = GcsPath("fixtures-for-tests", "thisdoesntexist/now/or/inthefuture.nope")

  // NB: for requests to nonexistent objects, Google returns a 403 unless the user ALSO has permission to
  // list the contents of the associated bucket, in which case Google returns a 404. In this suite, we do not
  // attempt to test the various error codes that Google is responsible for; we simply want to test that when
  // we get an error code back from Google, we propagate it to the user. Therefore, we have tests here that
  // check for a 403 when requesting non-existent objects, which may seem wrong at first glance to anyone
  // reading this code.

  "cookie-authed download endpoint" - {

    "should return 403 for a non-existent object" in {
      implicit val authToken: AuthToken = student.makeAuthToken()
      val response = Orchestration.storage.getObjectDownload(nonExistent.bucketName, nonExistent.objectName)
      assertResult(StatusCodes.Forbidden) { response.status }
    }

    "should return 403 for a file I don't have permissions to" in {
      implicit val authToken: AuthToken = student.makeAuthToken()
      val response = Orchestration.storage.getObjectDownload(noAccess.bucketName, noAccess.objectName)
      assertResult(StatusCodes.Forbidden) { response.status }
    }

    "should return OK for small (<8MB) public files" in {
      implicit val authToken: AuthToken = student.makeAuthToken()
      val response:HttpResponse = Orchestration.storage.getObjectDownload(publicUrl.bucketName, publicUrl.objectName)
      assertResult(StatusCodes.OK) { response.status }
      val contentLength:Long = response.header[`Content-Length`].map(_.length).getOrElse(-1)
      assert(contentLength < 8 * 1024 * 1024, s"content-length should be under 8MB; was $contentLength" )
    }

    "should return content directly for small (<8MB) files" in {
      implicit val authToken: AuthToken = student.makeAuthToken()
      withSmallFile { smallFile =>
        setStudentOnly(smallFile, student)
        val response = eventually {
          val response: HttpResponse = Orchestration.storage.getObjectDownload(smallFile.bucketName, smallFile.objectName)
          assertResult(StatusCodes.OK) {
            response.status
          }
          response
        }(PatienceConfig(timeout = 10.minutes, interval = 30.seconds), implicitly, implicitly)
        val contentLength:Long = response.header[`Content-Length`].map(_.length).getOrElse(-1)
        assert(contentLength < 8 * 1024 * 1024, s"content-length should be under 8MB; was $contentLength" )
        val responseString: String = Await.result(response.entity.toStrict(2.minutes).map(_.data.utf8String), 2.minutes)
        assertResult("this is a small text file.") { responseString }
      }
    }

    "should redirect to a signed url for large (>8MB) files" in {
      implicit val authToken: AuthToken = student.makeAuthToken()
      withLargeFile { largeFile =>
        setStudentAndSA(largeFile, student)
        val response = eventually {
          val response: HttpResponse = Orchestration.storage.getObjectDownload(largeFile.bucketName, largeFile.objectName)
          assertResult(StatusCodes.TemporaryRedirect) {
            response.status
          }
          response
        }(PatienceConfig(timeout = 10.minutes, interval = 30.seconds), implicitly, implicitly)
        val redirectUriOption = response.header[Location].map(_.getUri())
        assert(redirectUriOption.isDefined, "redirect should have a Location header")
        redirectUriOption.map { redirectUri =>
          assert(redirectUri.query().toMultiMap.containsKey("GoogleAccessId"), "signed url should have GoogleAccessId query param")
          assert(redirectUri.query().toMultiMap.containsKey("Expires"), "signed url should have Expires query param")
          assert(redirectUri.query().toMultiMap.containsKey("Signature"), "signed url should have Signature query param")
          assert(redirectUri.path().contains(largeFile.bucketName), "signed url should contain bucket")
          assert(redirectUri.path().contains(largeFile.objectName), "signed url should contain object key")
        }
      }
    }

    /*  David An 30-Sep-19: disabling this test. We recently re-evaluated our SA permissions, removing many that were
        unnecessary. SA now has storage-admin, which means this test is failing, since the SA *does* have permission
        to sign the URL.

        End-user functionality remains fine. The test is faulty. The test will need to be rewritten such that after
        it grants the student access to the file, it removes the SA's permission.

        AS-2: rewrite/refactor/re-enable
     */
    "should redirect to a direct-download url when SA doesn't have signing permissions for large (>8MB) files " ignore {
      implicit val authToken: AuthToken = student.makeAuthToken()
      withLargeFile { largeFile =>
        setStudentOnly(largeFile, student)
        val response = eventually {
          val response: HttpResponse = Orchestration.storage.getObjectDownload(largeFile.bucketName, largeFile.objectName)
          assertResult(StatusCodes.TemporaryRedirect) {
            response.status
          }
          response
        }(PatienceConfig(timeout = 10.minutes, interval = 30.seconds), implicitly, implicitly)
        val redirectUriOption = response.header[Location].map(_.getUri())
        assert(redirectUriOption.isDefined, "redirect should have a Location header")
        redirectUriOption.map { redirectUri =>
          val expected = s"https://storage.cloud.google.com/${largeFile.bucketName.value}/${largeFile.objectName.value}"
          assertResult(expected) { redirectUri.toString }
        }
      }
    }

    "should propagate inferred content-type" in {
      implicit val authToken: AuthToken = student.makeAuthToken()
      val response:HttpResponse = Orchestration.storage.getObjectDownload(pngFile.bucketName, pngFile.objectName)
      assertResult(StatusCodes.OK) { response.status }
      val contentType = response.header[`Content-Type`].map(_.contentType.toString())
      assertResult(Some("image/png"),
        "content type for png should be inferred and returned") {
        contentType
      }
    }

    "should propagate overriden content-type" in {
      implicit val authToken: AuthToken = student.makeAuthToken()
      val response:HttpResponse = Orchestration.storage.getObjectDownload(overriddenContentType.bucketName, overriddenContentType.objectName)
      assertResult(StatusCodes.OK) { response.status }
      val contentType = response.header[`Content-Type`].map(_.contentType.toString())
      assertResult(Some("application/json"),
        "content type for png should be inferred and returned") {
        contentType
      }
    }

  }

}
