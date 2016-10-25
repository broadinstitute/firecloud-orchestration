package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.Notification

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


/**
 * Created by mbemis on 10/25/16.
 *
 * Not currently used; serves as example code only
 *
 */
class MockThurloeDAO extends ThurloeDAO {

  override def sendNotifications(notifications: List[Notification]): Future[Boolean] = Future(true)

}
