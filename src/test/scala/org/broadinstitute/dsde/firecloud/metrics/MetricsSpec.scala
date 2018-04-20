package org.broadinstitute.dsde.firecloud.metrics

import org.broadinstitute.dsde.firecloud.dataaccess.{MockLogitDAO, MockRawlsDAO, MockSearchDAO}
import org.broadinstitute.dsde.firecloud.model.Metrics
import org.broadinstitute.dsde.firecloud.model.Metrics._
import org.broadinstitute.dsde.firecloud.model.MetricsFormat.LogitMetricFormat
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.joda.time.DateTime
import org.scalatest.Assertions
import spray.json.{JsNumber, JsObject, JsString}
import akka.pattern._
import org.broadinstitute.dsde.firecloud.FireCloudException

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class MetricsSpec extends BaseServiceSpec {

  implicit val duration: Duration = 2.minutes
  implicit val askTimeout: akka.util.Timeout = 2.minutes

  "JSON serialization for metrics models" - {
    "should serialize noop metric to empty object" in {
      assertResult(JsObject()) {LogitMetricFormat.write(NoopMetric)}
    }
    "should serialize NumSamples metric correctly" in {
      List(Integer.MIN_VALUE, -1, 0, 1, 42, 123456789, Integer.MAX_VALUE) foreach { num =>
        val numSamples = NumSamples(num)
        val expected = JsObject(Map("metricType" -> JsString("NumSamples"), "numSamples" -> JsNumber(num)))
        assertResult(expected) {LogitMetricFormat.write(numSamples)}
      }
    }
    "should serialize NumSubjects metric correctly" in {
      List(Integer.MIN_VALUE, -1, 0, 1, 42, 123456789, Integer.MAX_VALUE) foreach { num =>
        val numSubjects = NumSubjects(num)
        val expected = JsObject(Map("metricType" -> JsString("NumSubjects"), "numSubjects" -> JsNumber(num)))
        assertResult(expected) {LogitMetricFormat.write(numSubjects)}
      }
    }
    "should serialize SamplesAndSubjects metric correctly" in {
      val nums = List(Integer.MIN_VALUE, -1, 0, 1, 42, 123456789, Integer.MAX_VALUE)
      val numPairs = nums zip nums
      numPairs foreach { case (numSamples:Int, numSubjects:Int) =>
        val ss = SamplesAndSubjects(numSamples, numSubjects)
        val expected = JsObject(Map("metricType" -> JsString("SamplesAndSubjects"),
          "numSamples" -> JsNumber(numSamples), "numSubjects" -> JsNumber(numSubjects)))
        assertResult(expected) {LogitMetricFormat.write(ss)}
      }
    }
  }

  "MetricsActor" - {
    "should be resilient to failures retrieving stats from rawls" in {
      val seed = 123+scala.util.Random.nextInt(1000)
      val testApp = app.copy(
        rawlsDAO = new MetricsSpecMockRawlsDAO(numFailures=2, numSamples=seed),
        searchDAO = new MetricsSpecMockSearchDAO(numFailures=0, numSubjects=222),
        logitDAO = new MetricsSpecMockLogitDAO(numFailures=0))
      val metricsActor = system.actorOf(MetricsActor.props(testApp), "metrics-spec-failing-rawls-actor")
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(SamplesAndSubjects(seed, 222)) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect success
    }
    "should be resilient to failures retrieving stats from elasticsearch" in {
      val seed = 123+scala.util.Random.nextInt(1000)
      val testApp = app.copy(
        rawlsDAO = new MetricsSpecMockRawlsDAO(numFailures=0, numSamples=111),
        searchDAO = new MetricsSpecMockSearchDAO(numFailures=3, numSubjects=seed),
        logitDAO = new MetricsSpecMockLogitDAO(numFailures=0))
      val metricsActor = system.actorOf(MetricsActor.props(testApp), "metrics-spec-failing-search-actor")
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(SamplesAndSubjects(111, seed)) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect success
    }
    "should be resilient to failures sending data to logit" in {
      val seed = 123+scala.util.Random.nextInt(1000)
      val testApp = app.copy(
        rawlsDAO = new MetricsSpecMockRawlsDAO(numFailures=0, numSamples=seed),
        searchDAO = new MetricsSpecMockSearchDAO(numFailures=0, numSubjects=seed+1),
        logitDAO = new MetricsSpecMockLogitDAO(numFailures=4))
      val metricsActor = system.actorOf(MetricsActor.props(testApp), "metrics-spec-failing-logit-actor")
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(SamplesAndSubjects(seed, seed+1)) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect success
    }
    "should extract number of samples and subject from rawls and elasticsearch and send to logit" in {
      val seed = 123+scala.util.Random.nextInt(1000)
      val testApp = app.copy(
        rawlsDAO = new MetricsSpecMockRawlsDAO(numFailures=0, numSamples=seed),
        searchDAO = new MetricsSpecMockSearchDAO(numFailures=0, numSubjects=seed-2),
        logitDAO = new MetricsSpecMockLogitDAO(numFailures=0))
      val metricsActor = system.actorOf(MetricsActor.props(testApp), "metrics-spec-actor")
      // MetricsActor operates on a repeating schedule (e.g. every 6 hours). Mimic that here
      // by asking it for metrics twice in a row to make sure it operates correctly on the second try.
      assertResult(SamplesAndSubjects(seed, seed-2)) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) }
      assertResult(SamplesAndSubjects(seed, seed-2)) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) }
    }
  }

}

/* =======================================================================================
      Mock DAOs to support these tests. Each of these DAOs accepts a "numFailures" argument.
      When numFailures is non-zero, each DAO will throw an Exception/Future.failed
      the first $numFailures times it is called. This allows calling code to test resilience
      to errors.
   ======================================================================================= */


class MetricsSpecMockLogitDAO(numFailures: Int) extends MockLogitDAO {
  val q = new java.util.concurrent.ConcurrentLinkedQueue[Int]

  override def recordMetric(metric: LogitMetric): Future[LogitMetric] = {
    if (q.size() >= numFailures) {
      Future.successful(metric)
    } else {
      q.add(q.size())
      Future.failed(new MetricsException("intentional LogitDAO exception for MetricsSpec"))
    }
  }
}


class MetricsSpecMockRawlsDAO(numFailures: Int, numSamples: Int) extends MockRawlsDAO {
  val q = new java.util.concurrent.ConcurrentLinkedQueue[Int]

  override def adminStats(startDate: DateTime, endDate: DateTime, workspaceNamespace: Option[String], workspaceName: Option[String]): Future[Metrics.AdminStats] = {
    if (q.size() >= numFailures) {
      Future.successful(AdminStats("2001-01-01", "2002-02-02", Statistics(CurrentEntityStatistics(None, None, Map("sample"->numSamples, "participant"->numSamples*2)))))
    } else {
      q.add(q.size())
      Future.failed(new FireCloudException("intentional RawlsDAO exception for MetricsSpec"))
    }
  }
}

class MetricsSpecMockSearchDAO(numFailures: Int, numSubjects: Int) extends MockSearchDAO {
  val q = new java.util.concurrent.ConcurrentLinkedQueue[Int]

  override def statistics: LogitMetric = {
    if (q.size() >= numFailures) {
      NumSubjects(numSubjects)
    } else {
      q.add(q.size())
      throw new FireCloudException("intentional SearchDAO exception for MetricsSpec")
    }
  }
}
