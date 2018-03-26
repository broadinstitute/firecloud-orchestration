package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.Metrics.{AdminStats, CurrentEntityStatistics, LogitMetric, Statistics}
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject

object Metrics {

  /*


    "currentEntityStatistics": {
      "entityStats": {
        "pair_set": 32,
        "participant": 1361235,
        "sample_set": 370,
        "participant_set": 5018,
        "sample": 1620664,
        "pair": 14151
      },
      "workspaceNamespace": "broad-dsde-dev"
    },

   */

  // we could/should represent EntityStats as a Map[String, Int]
  // case class EntityStats(sample: Option[Int], pair: Option[Int], participant: Option[Int], sample_set: Option[Int], pair_set: Option[Int], participant_set: Option[Int])
  case class CurrentEntityStatistics(workspaceNamespace: Option[String], workspaceName: Option[String], entityStats: Map[String, Int])
  case class Statistics(currentEntityStatistics: CurrentEntityStatistics)
  case class AdminStats(startDate: String, endDate: String, statistics: Statistics)


  // TODO: build out
  case class LogitMetric(numSamples: Int)

}

// ModelJsonProtocol is getting big and unwieldy, keeping the json formats local to the model as a new paradigm
trait MetricsFormat {
  // implicit val EntityStatsFormat = jsonFormat6(EntityStats)
  implicit val CurrentEntityStatisticsFormat = jsonFormat3(CurrentEntityStatistics)
  implicit val StatisticsFormat = jsonFormat1(Statistics)
  implicit val AdminStatsFormat = jsonFormat3(AdminStats)

  implicit val LogitMetricFormat = jsonFormat1(LogitMetric)

}

object MetricsFormat extends MetricsFormat
