package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.ShareLog.{Share, ShareType}
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

object ShareLogDAO {
  lazy val serviceName = "ShareLog"
}

trait ShareLogDAO extends ReportsSubsystemStatus with ElasticSearchDAOSupport {

  implicit val errorReportSource = ErrorReportSource(ShareLogDAO.serviceName)

  override def serviceName: String = ShareLogDAO.serviceName

  /**
    * Logs a record of a user sharing a workspace, group, or method with a user.
    *
    * @param userId The workbench user id
    * @param sharee The email of the user being shared with
    * @param shareType The type (workspace, group, or method) see `ShareLog`
    * @return The record of the share - see `ShareLog.Share`
    */
  def logShare(userId: String, sharee: String, shareType: ShareType.Value): Share


  /**
    * Logs records of a user sharing a workspace, group, or method with users.
    *
    * @param userId The workbench user id
    * @param sharees The emails of the users being shared with
    * @param shareType The type (workspace, group, or method) see `ShareLog`
    * @return The records of the shares - see `ShareLog.Share`
    */
  def logShares(userId: String, sharees: Set[String], shareType: ShareType.Value): Set[Share]

  /**
    * Gets a share by the ID.
    *
    * @param share The share to get
    * @return A record of the share
    */
  def getShare(share: Share): Share

  /**
    * Gets all shares that have been logged for a workbench user which fall under the
    * given type of share (workspace, method, group).
    *
    * @param userId     The workbench user ID
    * @param shareType  The type (workspace, group, or method)
    * @return A list of `ShareLog.Share`s
    */
  def getShares(userId: String, shareType: Option[ShareType.Value] = None): Seq[Share]
}
