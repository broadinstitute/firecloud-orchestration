package org.broadinstitute.dsde.firecloud.utils

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait PerformanceLogging {

  val perfLogger: Logger = Logger(LoggerFactory.getLogger("PerformanceLogging"))

  def perfmsg(caller: String, msg: String, start: Long, finish: Long): String = {
    val elapsed = finish - start
    s"[$caller] took [$elapsed] ms, start [$start], finish [$finish]: $msg"
  }

}
