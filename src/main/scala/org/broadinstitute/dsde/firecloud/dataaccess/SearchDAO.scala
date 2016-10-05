package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import spray.json.JsObject

/**
  * Created by davidan on 9/28/16.
  */
trait SearchDAO extends LazyLogging {

  def deleteIndex

  def bulkIndex(docs: Seq[(String, JsObject)])

}
