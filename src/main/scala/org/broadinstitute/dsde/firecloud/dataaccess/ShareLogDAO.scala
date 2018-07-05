package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.ShareLog.Share
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
    * @return The record of the share
    */
  def logShare(userId: String, sharee: String, shareType: String): Share

  /**
    * Gets a share by the ID.
    *
    * @param id The ID of the share
    * @return A record of the share
    */
  def getShare(id: String): Share

  /**
    * Gets all shares that have been logged for a workbench user which fall under the
    * given type of share (workspace, method, group).
    *
    * @param userId     The workbench user ID
    * @param shareType  The type (workspace, group, or method)
    * @return A list of `ShareLog.Share`s
    */
  def getShares(userId: String, shareType: Option[String] = None): Seq[Share]

//  todo
//  def autocomplete(userId: String, term: String): List[String]

}
