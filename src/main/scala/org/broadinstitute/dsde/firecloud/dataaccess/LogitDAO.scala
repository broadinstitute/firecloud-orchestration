package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.Metrics.LogitMetric
import spray.json.JsObject

trait LogitDAO {

  def recordMetric(logtype: String, metric: LogitMetric)


}
