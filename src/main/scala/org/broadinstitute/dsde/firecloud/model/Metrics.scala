package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.Metrics._
import spray.json.DefaultJsonProtocol._
import spray.json.{JsNumber, JsObject, JsString, JsValue, RootJsonFormat}

object Metrics {

  final val LOG_TYPE = "FCMetric"
  final val METRICTYPE_KEY = "metricType"

  case class CurrentEntityStatistics(workspaceNamespace: Option[String], workspaceName: Option[String], entityStats: Map[String, Int])
  case class Statistics(currentEntityStatistics: CurrentEntityStatistics)
  case class AdminStats(startDate: String, endDate: String, statistics: Statistics)

  abstract class LogitMetric
  case object NoopMetric extends LogitMetric
  case class NumObjects(numSamples: Int) extends LogitMetric

}

// ModelJsonProtocol is getting big and unwieldy, keeping the json formats local to the model as a new paradigm
trait MetricsFormat {
  // implicit val EntityStatsFormat = jsonFormat6(EntityStats)
  implicit val CurrentEntityStatisticsFormat = jsonFormat3(CurrentEntityStatistics)
  implicit val StatisticsFormat = jsonFormat1(Statistics)
  implicit val AdminStatsFormat = jsonFormat3(AdminStats)

  implicit object LogitMetricFormat extends RootJsonFormat[LogitMetric] {
    override def write(obj: LogitMetric): JsValue = obj match {
      case NoopMetric => JsObject()
      case ns:NumObjects => JsObject(Map(METRICTYPE_KEY -> JsString("NumObjects"), "numSamples" -> JsNumber(ns.numSamples)))
    }

    override def read(json: JsValue): LogitMetric = ??? // no need for reads
  }
}

object MetricsFormat extends MetricsFormat
