package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.Metrics
import org.broadinstitute.dsde.firecloud.model.Metrics.NoopMetric

import scala.concurrent.Future

class NoopLogitDAO extends LogitDAO {
  override def recordMetric(metric: Metrics.LogitMetric): Future[Metrics.LogitMetric] =
    Future.successful(NoopMetric)
}
