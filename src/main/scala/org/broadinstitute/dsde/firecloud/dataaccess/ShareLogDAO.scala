package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.ShareLog.Share
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

object ShareLogDAO {
  lazy val serviceName = "ShareLog"
}

trait ShareLogDAO extends ReportsSubsystemStatus with ElasticSearchDAOSupport {

  implicit val errorReportSource = ErrorReportSource(ShareLogDAO.serviceName)

  override def serviceName: String = ShareLogDAO.serviceName

  def logShare(userId: String, sharee: String, shareType: String): Share

  def getShares(userId: String): List[Share]

  def autocomplete(userId: String, term: String): List[String]

}
