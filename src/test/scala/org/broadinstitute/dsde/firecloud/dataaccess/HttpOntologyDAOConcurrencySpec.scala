package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.mock.action.ExpectationCallback
import org.mockserver.model.HttpCallback.callback
import org.mockserver.model.{HttpRequest, HttpResponse}
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Ignore, Matchers}
import spray.http.StatusCodes.OK
import spray.json._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Random

@Ignore
class HttpOntologyDAOConcurrencySpec extends FreeSpec with Matchers with BeforeAndAfterAll with LazyLogging {

  import Counters._

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val system = ActorSystem("HttpOntologyDAOSpec")
  val dao = new HttpOntologyDAO

  var ontologyServer: ClientAndServer = _

  override def beforeAll = {
    ontologyServer = startClientAndServer(ontologyServerPort)
    val okGet = request().withMethod("GET").withPath("/search").withQueryStringParameter("id","123")
    ontologyServer.when(okGet).callback(callback().withCallbackClass("org.broadinstitute.dsde.firecloud.dataaccess.HttpOntologyDAOConcurrencyCallback"))
  }

  override def afterAll = {
    ontologyServer.stop()
  }

  "HttpOntologyDAO concurrency" - {

    "should obey the spray max-clients throttle" in {
      assert(throttleCount > 0, "spray.can.host-connector.max-connections should be greater than 0")

      var maxCount = 0
      var minCount = Int.MaxValue

      // start, in parallel, (throttleCount * 5) requests
      val futures = Await.result(
        Future.sequence(Seq.fill(throttleCount * 5)(dao.search("123"))),
        5.minutes
      )

      futures foreach { searchResult =>
        assert(searchResult.isDefined)
        val sr = searchResult.get
        assertResult(1) { sr.size }
        assertResult("disease") { sr.head.label }
        val responseCount = sr.head.id.toInt
        if (responseCount > maxCount) maxCount = responseCount
        if (responseCount < minCount) minCount = responseCount
      }

      assert(maxCount <= throttleCount, s"request count should always be under $throttleCount; found $maxCount")
      assertResult(throttleCount, s"request count should have reached its maximum throttle limit") { maxCount }
      assert(0 < minCount, s"request count should always be > 0; found $minCount")
    }
  }
}


object Counters extends Matchers {
  val throttleCount = ConfigFactory.load().getInt("spray.can.host-connector.max-connections")

  var q = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  def count = q.size()
  def inc() = q.add("foo")
  def dec() = q.poll()
}


class HttpOntologyDAOConcurrencyCallback extends ExpectationCallback with Matchers with LazyLogging {
  import Counters._

  val dummyResponse = """[
                        |  {
                        |    "id": "%s",
                        |    "ontology": "Disease",
                        |    "usable": true,
                        |    "label": "disease"
                        |  }
                        |]""".stripMargin

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
