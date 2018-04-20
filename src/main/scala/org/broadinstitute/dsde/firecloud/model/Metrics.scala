package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.Metrics._
import spray.json.DefaultJsonProtocol._
import spray.json.{JsNumber, JsObject, JsString, JsValue, RootJsonFormat}

object Metrics {

  final val LOG_TYPE = "FCMetric"
  final val METRICTYPE_KEY = "metricType"

  // structures returned by Rawls
  case class CurrentEntityStatistics(workspaceNamespace: Option[String], workspaceName: Option[String], entityStats: Map[String, Int])
  case class Statistics(currentEntityStatistics: CurrentEntityStatistics)
  case class AdminStats(startDate: String, endDate: String, statistics: Statistics)

  // structures sent to Logit and otherwise used in *LogitDAO
  abstract class LogitMetric
  case object NoopMetric extends LogitMetric
  case class NumObjects(numSamples: Int) extends LogitMetric
  case class NumSubjects(numSubjects: Int) extends LogitMetric
  case class SamplesAndSubjects(numSamples: Int, numSubjects: Int) extends LogitMetric

}

// ModelJsonProtocol is getting big and unwieldy, keeping the json formats local to the model
trait MetricsFormat {
  implicit val CurrentEntityStatisticsFormat = jsonFormat3(CurrentEntityStatistics)
  implicit val StatisticsFormat = jsonFormat1(Statistics)
  implicit val AdminStatsFormat = jsonFormat3(AdminStats)

  implicit object LogitMetricFormat extends RootJsonFormat[LogitMetric] {
    override def write(obj: LogitMetric): JsValue = obj match {
      case NoopMetric => JsObject()
      case ns:NumObjects => JsObject(Map(METRICTYPE_KEY -> JsString("NumObjects"), "numSamples" -> JsNumber(ns.numSamples)))
      case ns:NumSubjects => JsObject(Map(METRICTYPE_KEY -> JsString("NumSubjects"), "numSubjects" -> JsNumber(ns.numSubjects)))
      case ss:SamplesAndSubjects => JsObject(Map(
        METRICTYPE_KEY -> JsString("SamplesAndSubjects"),
        "numSamples" -> JsNumber(ss.numSamples),
        "numSubjects" -> JsNumber(ss.numSubjects)
      ))
    }

    override def read(json: JsValue): LogitMetric = ??? // no need for reads
  }
}

object MetricsFormat extends MetricsFormat
