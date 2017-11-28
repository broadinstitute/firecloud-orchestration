package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.Trial.TrialProject
import org.broadinstitute.dsde.firecloud.model.WorkbenchUserInfo
import org.broadinstitute.dsde.rawls.model.{ErrorReportSource, RawlsBillingProjectName}

object TrialDAO {
  lazy val serviceName = "TrialDAO"
}

trait TrialDAO extends ReportsSubsystemStatus with ElasticSearchDAOSupport {

  override def serviceName:String = TrialDAO.serviceName
  implicit val errorReportSource = ErrorReportSource(TrialDAO.serviceName)

  def getProject(projectName: RawlsBillingProjectName): TrialProject
  def createProject(projectName: RawlsBillingProjectName): TrialProject
  def verifyProject(projectName: RawlsBillingProjectName, verified: Boolean): TrialProject
  def claimProject(userInfo: WorkbenchUserInfo): TrialProject
  def countAvailableProjects: Long
  def projectReport: Seq[TrialProject] // return a list of projects-to-users

}
