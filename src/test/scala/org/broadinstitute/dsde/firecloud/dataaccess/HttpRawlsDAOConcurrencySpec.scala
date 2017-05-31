package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.{EntityQuery, EntityQueryResponse, SortDirections}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.mock.action.ExpectationCallback
import org.mockserver.model.HttpCallback.callback
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.{HttpRequest, HttpResponse}
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import spray.http.StatusCodes.OK
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class HttpRawlsDAOConcurrencySpec extends FreeSpec with Matchers with BeforeAndAfterAll with LazyLogging {

  import Counters._

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val system = ActorSystem("HttpRawlsDAOConcurrencySpec")
  val dao = new HttpRawlsDAO

  var rawlsServer: ClientAndServer = _

  val (workspaceNamespace, workspaceName, entityType) = ("workspaceNamespace", "workspaceName", "participant")

  override def beforeAll = {
    rawlsServer = startClientAndServer(workspaceServerPort)
    val okGet = request().withMethod("GET").withPath(s"/api/workspaces/$workspaceNamespace/$workspaceName/entityQuery/$entityType")
    rawlsServer.when(okGet).callback(callback().withCallbackClass("org.broadinstitute.dsde.firecloud.dataaccess.HttpRawlsDAOConcurrencySpecCallback"))
  }

  override def afterAll = {
    rawlsServer.stop()
  }

  "HttpRawlsDAO concurrency" - {

    "should obey the spray max-clients throttle" in {
      assert(throttleCount > 0, "spray.can.host-connector.max-connections should be greater than 0")

      var maxCount = 0
      var minCount = Int.MaxValue
      implicit val userToken: UserInfo = UserInfo("accessToken", "subjectId")
      // start, in parallel, (throttleCount * 5) requests
      val query: EntityQuery = EntityQuery(page = 1, pageSize = 1, sortField = entityType, sortDirection = SortDirections.Ascending, filterTerms = None)
      val futures: Seq[EntityQueryResponse] = Await.result(
        Future.sequence(Seq.fill(throttleCount * 5)(dao.queryEntitiesOfType(workspaceNamespace, workspaceName, entityType, query))),
        5.minutes
      )

      futures foreach { searchResult: EntityQueryResponse =>
        assert(searchResult.parameters.pageSize == 1)
        val sr = searchResult.results
        assertResult(1) { sr.size }
        assertResult(entityType) { sr.head.entityType }
        val responseCount = sr.head.name.toInt
        if (responseCount > maxCount) maxCount = responseCount
        if (responseCount < minCount) minCount = responseCount
      }

      assert(maxCount <= throttleCount, s"request count should always be under $throttleCount; found $maxCount")
      assertResult(throttleCount, s"request count should have reached its maximum throttle limit") { maxCount }
      assert(0 < minCount, s"request count should always be > 0; found $minCount")
    }
  }
}

class HttpRawlsDAOConcurrencySpecCallback extends ExpectationCallback with Matchers with LazyLogging {
  import Counters._

  val dummyResponse = """{
                        |  "parameters": {
                        |    "page": 1,
                        |    "pageSize": 1,
                        |    "sortField": "name",
                        |    "sortDirection": "asc"
                        |  },
                        |  "resultMetadata": {
                        |    "unfilteredCount": 500,
                        |    "filteredCount": 500,
                        |    "filteredPageCount": 500
                        |  },
                        |  "results": [
                        |    {
                        |      "name": "%s",
                        |      "entityType": "participant",
                        |      "attributes": {
                        |        "prop_1": "dd7558a3-8c56-4c6f-8d4a-6096021366bf",
                        |        "prop_2": "b9f4f1fd-a229-494e-a300-8b468b189b76",
                        |        "prop_3": "64867491-b006-4277-bb59-48971d9f8748"
                        |      }
                        |    }
                        |  ]
                        |}""".stripMargin

  override def handle(httpRequest: HttpRequest): HttpResponse = {
    inc()
    Thread.sleep(250 + Random.nextInt(500))
    // return the current count of simultaneous requests in the response so the calling test can assert on it
    val content = dummyResponse.format(count).parseJson.prettyPrint
    val resp = response().withHeaders(MockUtils.header).withStatusCode(OK.intValue).withBody(content)
    dec()
    resp
  }
}
