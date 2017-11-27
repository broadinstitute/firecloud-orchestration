package org.broadinstitute.dsde.firecloud.utils

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait PerformanceLogging {

  val perfLogger: Logger = Logger(LoggerFactory.getLogger("PerformanceLogging"))

  def perfmsg(caller: String, time: Long) =              s"[$caller] took [$time]"
  def perfmsg(caller: String, time: Long, msg: String) = s"[$caller] took [$time]: $msg"

}
