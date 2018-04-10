package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.Metrics
import spray.json.JsObject

import scala.concurrent.Future

class MockLogitDAO extends LogitDAO {
  override def recordMetric(metric: Metrics.LogitMetric): Future[Metrics.LogitMetric] = ???
}
