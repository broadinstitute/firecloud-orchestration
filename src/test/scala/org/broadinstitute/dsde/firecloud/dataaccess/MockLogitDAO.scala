package org.broadinstitute.dsde.firecloud.dataaccess
import spray.json.JsObject

class MockLogitDAO extends LogitDAO {
  override def recordMetric(logtype: String, metric: JsObject): Unit = ???
}
