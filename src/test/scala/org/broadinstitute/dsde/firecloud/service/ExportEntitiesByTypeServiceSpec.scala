package org.broadinstitute.dsde.firecloud.service

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.ContentDispositionTypes
import akka.stream.ActorMaterializer
import org.broadinstitute.dsde.firecloud.dataaccess.MockRawlsDAO
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.webservice.{CookieAuthedApiService, ExportEntitiesApiService}
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.{ContentType, ContentTypes, FormData, HttpCharsets, HttpEntity, HttpMethods, MediaTypes, Uri}
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Connection, `Content-Disposition`}
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.http.scaladsl.unmarshalling.Unmarshal
import better.files.File
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.workbench.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, spy, times, verify}
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

class ExportEntitiesByTypeServiceSpec extends BaseServiceSpec with ExportEntitiesApiService with CookieAuthedApiService with BeforeAndAfterEach {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  // On travis, slow processing causes the route to timeout and complete too quickly for the large content checks.
  override implicit val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(30.seconds)

  def actorRefFactory: ActorSystem = system

  // spy on the MockGoogleServicesDAO. Note MockGoogleServicesDAO is a (non-mockito) concrete implementation, so this is
  // adding a mockito layer on top of that.
  val mockitoGoogleServicesDao = spy(classOf[MockGoogleServicesDAO])

  val exportEntitiesByTypeConstructor: ExportEntitiesByTypeArguments => ExportEntitiesByTypeActor =
    ExportEntitiesByTypeActor.constructor(app.copy(googleServicesDAO = mockitoGoogleServicesDao), actorRefFactory)

  val largeFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/large/entities/sample/tsv"
  val largeFireCloudEntitiesSampleSetTSVPath = "/api/workspaces/broad-dsde-dev/largeSampleSet/entities/sample_set/tsv"
  val validFireCloudEntitiesSampleSetTSVPath = "/api/workspaces/broad-dsde-dev/valid/entities/sample_set/tsv"
  val validFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/valid/entities/sample/tsv"
  val invalidFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/invalid/entities/sample/tsv"
  val invalidFireCloudEntitiesParticipantSetTSVPath = "/api/workspaces/broad-dsde-dev/invalid/entities/participant_set/tsv"
  val exceptionFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/exception/entities/sample/tsv"
  val page3ExceptionFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/page3exception/entities/sample/tsv"
  val nonModelEntitiesBigQueryTSVPath = "/api/workspaces/broad-dsde-dev/nonModel/entities/bigQuery/tsv"
  val nonModelEntitiesBigQuerySetTSVPath = "/api/workspaces/broad-dsde-dev/nonModelSet/entities/bigQuery_set/tsv"
  val nonModelEntitiesPairTSVPath = "/api/workspaces/broad-dsde-dev/nonModelPair/entities/pair/tsv"

  // Pick the first few headers from the list of available sample headers:
  val filterProps: Seq[String] = MockRawlsDAO.largeSampleHeaders.take(5).map(_.name)
  // Grab the rest so we can double check the returned content to make sure the ignored ones aren't in the response.
  val missingProps: Seq[String] = MockRawlsDAO.largeSampleHeaders.drop(5).map(_.name)

  override protected def beforeEach(): Unit = {
    reset(mockitoGoogleServicesDao)
    super.beforeEach()
  }

  "ExportEntitiesApiService-ExportEntitiesByType" - {

    "when an exception occurs in a paged query response, the response should be handled appropriately" - {
      "FireCloudException is contained in response chunks" in {
        // Exception case is generated from the entity query call which is inside of the akka stream code.
        Get(page3ExceptionFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          val strResp = responseAs[String]
          strResp should include ("FireCloudException")
        }
      }
    }

    "when an exception occurs, the response should be handled appropriately" - {
      "InternalServerError is returned" in {
        // Exception case is generated from the entity query call which is inside of the akka stream code.
        Get(exceptionFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(InternalServerError)
          errorReportCheck("Rawls", InternalServerError)
        }
      }
    }

    "when calling GET on exporting a valid entity type with filtered attributes" - {
      "OK response is returned and attributes are filtered" in {
        val uri = Uri(largeFireCloudEntitiesSampleTSVPath).withQuery(Query(("attributeNames", filterProps.mkString(","))))
        Get(uri) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers should contain(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "sample.tsv")))
          contentType shouldEqual ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
          responseAs[String].startsWith("update:") should be(true)
          validateProps(response.entity)
        }
      }
    }

    "when calling GET on exporting a non-FC model entity type with all attributes" - {
      "OK response is returned and attributes are included and model is flexible" in {
        Get(nonModelEntitiesBigQueryTSVPath+"?model=flexible") ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers should contain(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "bigQuery.tsv")))
          contentType shouldEqual ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
          responseAs[String].contains("query_str") should be(true)
        }
      }
      "400 response is returned is model is firecloud" in {
        Get(nonModelEntitiesBigQueryTSVPath+"?model=firecloud") ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(BadRequest)
        }
      }
    }

    "when calling GET on exporting a non-FC model entity type with selected attributes" - {
      "OK response is returned and file is entity type when model is flexible" in {
        Get(nonModelEntitiesPairTSVPath + "?attributeNames=names&model=flexible") ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers should contain(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "pair.tsv")))
          contentType shouldEqual ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
          responseAs[String].startsWith("entity:") should be(true)
          responseAs[String].contains("names") should be(true)
        }
      }
    }

    "when calling GET on exporting a non-FC model entity set type with all attributes" - {
      "400 response is returned when model defaults to firecloud" in {
        Get(nonModelEntitiesBigQuerySetTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(BadRequest)
        }
      }
    }

    "when calling GET on exporting LARGE (20K) sample TSV" - {
      "OK response is returned" in {
        Get(largeFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers should contain(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "sample.tsv")))
          contentType shouldEqual ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
        }
      }
    }

    "when calling GET on exporting LARGE (5K) sample set file" - {
      "OK response is returned" in {
        Get(largeFireCloudEntitiesSampleSetTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity.isKnownEmpty() shouldNot be(true) // Entity is the first line of content as output by StreamingActor
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers.contains(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "sample_set.zip"))) should be(true)
        }
      }
    }

    "when calling GET on exporting a valid collection type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSampleSetTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity.isKnownEmpty() shouldNot be(true)
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers should contain(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "sample_set.zip")))
          contentType shouldEqual ContentTypes.`application/octet-stream`
        }
      }
    }

    "when calling GET on exporting a valid entity type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity.isKnownEmpty() shouldNot be(true)
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers should contain(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "sample.tsv")))
          contentType shouldEqual ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
        }
      }
    }

    "when calling GET on exporting an invalid collection type" - {
      "NotFound response is returned" in {
        Get(invalidFireCloudEntitiesParticipantSetTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(NotFound)
        }
      }
    }

    "when calling GET on exporting an invalid entity type" - {
      "NotFound response is returned" in {
        Get(invalidFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

  }

  // TSV save-to-bucket shares a lot of code with TSV download. Therefore, most functionality is tested in the
  // "ExportEntitiesApiService-ExportEntitiesByType" tests just above. Here, we add some additional tests that are
  // specific to save-to-bucket.
  "ExportEntitiesApiService-save to bucket" - {

    "when calling GET on exporting a valid collection type" - {
      "OK response is returned" in {
        Post("/api/workspaces/broad-dsde-dev/valid/entities/sample_set/tsv/save") ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          verify(mockitoGoogleServicesDao, times(1)).writeObjectAsRawlsSA(any[GcsBucketName], any[GcsObjectName], any[File])
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          // gs://bucketName/tsvexport/sample_set/sample_set-1727724455587.zip
          result should fullyMatch regex """gs:\/\/bucketName\/tsvexport\/sample_set\/sample_set-[0-9]{13}.zip"""
        }
      }
    }

    "when calling GET on exporting a valid entity type" - {
      "OK response is returned" in {
        Post("/api/workspaces/broad-dsde-dev/valid/entities/sample/tsv/save") ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          verify(mockitoGoogleServicesDao, times(1)).writeObjectAsRawlsSA(any[GcsBucketName], any[GcsObjectName], any[File])
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          // gs://bucketName/tsvexport/sample/sample-1727724455587.tsv
          result should fullyMatch regex """gs:\/\/bucketName\/tsvexport\/sample\/sample-[0-9]{13}.tsv"""
        }
      }
    }

  }

  val validCookieFireCloudEntitiesLargeSampleTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/large/entities/sample/tsv"
  val validCookieFireCloudEntitiesSampleSetTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/valid/entities/sample_set/tsv"
  val validCookieFireCloudEntitiesSampleTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/valid/entities/sample/tsv"
  val invalidCookieFireCloudEntitiesSampleTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/invalid/entities/sample/tsv"
  val invalidCookieFireCloudEntitiesParticipantSetTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/invalid/entities/participant_set/tsv"
  val exceptionCookieFireCloudEntitiesSampleTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/exception/entities/sample/tsv"
  val page3ExceptionCookieFireCloudEntitiesSampleTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/page3exception/entities/sample/tsv"

  "CookieAuthedApiService-ExportEntitiesByType" - {

    "when an exception occurs in a paged query response, the response should be handled appropriately" - {
      "FireCloudException is contained in response chunks" in {
        // Exception case is generated from the entity query call which is inside of the akka stream code.
        Post(page3ExceptionCookieFireCloudEntitiesSampleTSVPath, FormData(Map("FCtoken"->"token"))) ~> dummyUserIdHeaders("1234") ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          val strResp = responseAs[String]
          strResp should include ("FireCloudException")
        }
      }
    }

    "when an exception occurs, the response should be handled appropriately" - {
      "InternalServerError is returned" in {
        // Exception case is generated from the entity query call which is inside of the akka stream code.
        Post(exceptionCookieFireCloudEntitiesSampleTSVPath, FormData(Map("FCtoken"->"token"))) ~> dummyUserIdHeaders("1234") ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(InternalServerError)
          errorReportCheck("Rawls", InternalServerError)
        }
      }
    }

    "when calling POST on exporting a valid entity type with filtered attributes" - {
      "OK response is returned and attributes are filtered" in {
        Post(validCookieFireCloudEntitiesLargeSampleTSVPath, FormData(Map("FCtoken"->"token", "attributeNames"->filterProps.mkString(",")))) ~> dummyUserIdHeaders("1234") ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers should contain(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "sample.tsv")))
          contentType shouldEqual ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
          validateProps(response.entity)
        }
      }
    }

    "when calling POST on exporting LARGE (20K) sample TSV" - {
      "OK response is returned" in {
        Post(validCookieFireCloudEntitiesLargeSampleTSVPath, FormData(Map("FCtoken"->"token"))) ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity.isKnownEmpty() shouldNot be(true)
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers should contain(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "sample.tsv")))
          contentType shouldEqual ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
        }
      }
    }

    "when calling POST on exporting a valid collection type" - {
      "OK response is returned" in {
        Post(validCookieFireCloudEntitiesSampleSetTSVPath, FormData(Map("FCtoken"->"token"))) ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity.isKnownEmpty() shouldNot be(true)
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers.contains(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "sample_set.zip"))) should be(true)
        }
      }
    }

    "when calling POST on exporting a valid entity type" - {
      "OK response is returned" in {
        Post(validCookieFireCloudEntitiesSampleTSVPath, FormData(Map("FCtoken"->"token"))) ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity.isKnownEmpty() shouldNot be(true)
          headers.contains(Connection("Keep-Alive")) should be(true)
          headers should contain(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "sample.tsv")))
          contentType shouldEqual ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
        }
      }
    }

    "when calling POST on exporting an invalid collection type" - {
      "NotFound response is returned" in {
        Post(invalidCookieFireCloudEntitiesParticipantSetTSVPath, FormData(Map("FCtoken"->"token"))) ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(NotFound)
        }
      }
    }

    "when calling POST on exporting an invalid entity type" - {
      "NotFound response is returned" in {
        Post(invalidCookieFireCloudEntitiesSampleTSVPath, FormData(Map("FCtoken"->"token"))) ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling PUT, PATCH, DELETE on export path" - {
      "MethodNotAllowed response is returned" in {
        List(HttpMethods.PUT, HttpMethods.DELETE, HttpMethods.PATCH) foreach { method =>
          new RequestBuilder(method)(invalidCookieFireCloudEntitiesParticipantSetTSVPath) ~> sealRoute(cookieAuthedRoutes) ~> check {
            handled should be(true)
            withClue(s"Method $method:") {
              status should equal(MethodNotAllowed)
            }
          }
        }
      }
    }

    "when calling GET on exporting a valid entity type with filtered attributes" - {
      "OK response is returned and attributes are filtered" in {
        Get(s"$validCookieFireCloudEntitiesLargeSampleTSVPath?attributeNames=${filterProps.mkString(",")}") ~>
          dummyUserIdHeaders("1234") ~>
          dummyCookieAuthHeaders ~>
          sealRoute(cookieAuthedRoutes) ~> check {
            handled should be(true)
            status should be(OK)
            headers.contains(Connection("Keep-Alive")) should be(true)
            headers should contain(`Content-Disposition`.apply(ContentDispositionTypes.attachment, Map("filename" -> "sample.tsv")))
            contentType shouldEqual ContentType(MediaTypes.`text/tab-separated-values`, HttpCharsets.`UTF-8`)
            validateProps(response.entity)
          }
      }
    }
  }

  private def validateProps(entity: HttpEntity): Unit = {
    val entityHeaderString = Await.result(entity.toStrict(1.second).map(_.data.utf8String), Duration.Inf)
    filterProps.foreach { h => entityHeaderString.contains(h) should be(true) }
    missingProps.foreach { h => entityHeaderString.contains(h) should be(false) }
  }

  private def validateErrorInLastChunk(chunks: Seq[ChunkStreamPart], message: String): Unit = {
    chunks.reverse.head.data.utf8String should include (message)
  }

}
