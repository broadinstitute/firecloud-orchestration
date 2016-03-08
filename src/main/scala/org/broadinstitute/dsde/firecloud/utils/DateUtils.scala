package org.broadinstitute.dsde.firecloud.utils

import org.ocpsoft.prettytime.PrettyTime
import org.joda.time.{Seconds, Hours, DateTime}


object DateUtils {

  val EPOCH = 1000L


  def nowPlus30Days: Long = {
    nowDateTime.plusDays(30).getMillis / EPOCH
  }

  def nowMinus30Days: Long = {
    nowDateTime.minusDays(30).getMillis / EPOCH
  }

  def nowPlus24Hours: Long = {
    nowDateTime.plusHours(24).getMillis / EPOCH
  }

  def nowMinus24Hours: Long = {
    nowDateTime.minusHours(24).getMillis / EPOCH
  }

  def nowPlus1Hour: Long = {
    nowDateTime.plusHours(1).getMillis / EPOCH
  }

  def nowMinus1Hour: Long = {
    nowDateTime.minusHours(1).getMillis / EPOCH
  }

  def hoursSince(seconds: Long): Int = {
    Hours.hoursBetween(dtFromSeconds(seconds), nowDateTime).getHours
  }

  def hoursUntil(seconds: Long): Int = {
    Hours.hoursBetween(nowDateTime, dtFromSeconds(seconds)).getHours
  }

  def secondsSince(seconds: Long): Int = {
    Seconds.secondsBetween(dtFromSeconds(seconds), nowDateTime).getSeconds
  }


  def prettySince(seconds: Long): String = {
    new PrettyTime().format(new java.util.Date(seconds*EPOCH))
  }


  def now: Long = {
    nowDateTime.getMillis / EPOCH
  }

  def nowDateTime: DateTime = {
    dtFromMillis(System.currentTimeMillis())
  }

  def dtFromMillis(millis: Long): DateTime = {
    new DateTime(millis)
  }

  def dtFromSeconds(seconds: Long): DateTime = {
    new DateTime(seconds * EPOCH)
  }


}
