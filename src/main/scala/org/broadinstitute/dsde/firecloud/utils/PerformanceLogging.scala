package org.broadinstitute.dsde.firecloud.utils

import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait PerformanceLogging {

  val perfLogger: Logger = Logger(LoggerFactory.getLogger("PerformanceLogging"))
  val systemTimeZone = ZoneId.systemDefault()
  val dateFormatter = DateTimeFormatter.ISO_LOCAL_TIME

  def perfmsg(caller: String, msg: String, start: Instant, finish: Instant): String = {
    lazy val elapsed = finish.toEpochMilli - start.toEpochMilli

    lazy val startStr = LocalDateTime.ofInstant(start, systemTimeZone).format(dateFormatter)
    lazy val finishStr = LocalDateTime.ofInstant(finish, systemTimeZone).format(dateFormatter)

    s"[$caller] took [$elapsed] ms, start [$startStr], finish [$finishStr]: $msg"
  }

}
