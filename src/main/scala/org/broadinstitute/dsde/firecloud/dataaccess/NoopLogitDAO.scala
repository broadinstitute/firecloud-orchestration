package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.Metrics.{LogitMetric, NoopMetric}

import scala.concurrent.Future

class NoopLogitDAO extends LogitDAO {
  override def recordMetric(metric: LogitMetric): Future[LogitMetric] =
    Future.successful(NoopMetric)
}
