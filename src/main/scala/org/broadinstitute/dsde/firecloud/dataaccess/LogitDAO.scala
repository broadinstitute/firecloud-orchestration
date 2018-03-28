package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.Metrics.LogitMetric

import scala.concurrent.Future

trait LogitDAO {

  def recordMetric(metric: LogitMetric): Future[LogitMetric]


}
