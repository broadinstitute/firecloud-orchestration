package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.Notification

import scala.concurrent.Future
import scala.util.Try

/**
 * Created by mbemis on 10/21/16.
 */
trait ThurloeDAO extends LazyLogging {

  def sendNotifications(notifications: Seq[Notification]): Future[Try[Unit]]

}
