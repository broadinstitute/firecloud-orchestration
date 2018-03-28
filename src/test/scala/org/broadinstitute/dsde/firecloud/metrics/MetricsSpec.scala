package org.broadinstitute.dsde.firecloud.metrics

import org.broadinstitute.dsde.firecloud.dataaccess.{MockLogitDAO, MockRawlsDAO}
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
    "should serialize numobjects metric correctly" in {
      List(Integer.MIN_VALUE, -1, 0, 1, 42, 123456789, Integer.MAX_VALUE) foreach { num =>
        val numObjects = NumObjects(num)
        val expected = JsObject(Map("metricType" -> JsString("NumObjects"), "numSamples" -> JsNumber(num)))
        assertResult(expected) {LogitMetricFormat.write(numObjects)}
      }
    }
  }

  "MetricsActor" - {
    "should be resilient to failures retrieving stats from rawls" in {
      val seed = 123+scala.util.Random.nextInt(1000)
      val testApp = app.copy(rawlsDAO = new MetricsSpecMockRawlsDAO(numFailures=2, numSamples=seed), logitDAO = new MetricsSpecMockLogitDAO(numFailures=0, numSamples=seed))
      val metricsActor = system.actorOf(MetricsActor.props(testApp), "metrics-spec-failing-rawls-actor")
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(NumObjects(seed)) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect success
    }
    "should be resilient to failures sending data to logit" in {
      val seed = 123+scala.util.Random.nextInt(1000)
      val testApp = app.copy(rawlsDAO = new MetricsSpecMockRawlsDAO(numFailures=0, numSamples=seed), logitDAO = new MetricsSpecMockLogitDAO(numFailures=2, numSamples=seed))
      val metricsActor = system.actorOf(MetricsActor.props(testApp), "metrics-spec-failing-logit-actor")
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(NoopMetric) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect failure
      assertResult(NumObjects(seed)) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) } // expect success
    }
    "should extract number of samples from rawls and send to logit" in {
      val seed = 123+scala.util.Random.nextInt(1000)
      val testApp = app.copy(rawlsDAO = new MetricsSpecMockRawlsDAO(numFailures=0, numSamples=seed), logitDAO = new MetricsSpecMockLogitDAO(numFailures=0, numSamples=seed))
      val metricsActor = system.actorOf(MetricsActor.props(testApp), "metrics-spec-actor")
      assertResult(NumObjects(seed)) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) }
      assertResult(NumObjects(seed)) { Await.result(metricsActor ? MetricsActor.RecordMetrics, duration) }
    }
  }


}

class MetricsSpecMockLogitDAO(numFailures: Int, numSamples: Int) extends MockLogitDAO {
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
