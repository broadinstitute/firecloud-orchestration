package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses.CreationStatus
import org.broadinstitute.dsde.firecloud.model.Trial.TrialProject
import org.broadinstitute.dsde.firecloud.model.WorkbenchUserInfo
import org.broadinstitute.dsde.rawls.model.{ErrorReportSource, RawlsBillingProjectName}

object TrialDAO {
  lazy val serviceName = "TrialDAO"
}

trait TrialDAO extends ReportsSubsystemStatus with ElasticSearchDAOSupport {

  override def serviceName:String = TrialDAO.serviceName
  implicit val errorReportSource = ErrorReportSource(TrialDAO.serviceName)

  /**
    * Read the record for a specified project. Throws an error if record not found.
    *
    * @param projectName name of the project record to read.
    * @return the project record
    */
  def getProjectRecord(projectName: RawlsBillingProjectName): TrialProject

  /**
    * Check to see if the project record exists.
    *
    * @param projectName name of the project record to read.
    * @return whether or not the project record exists
    */
  def projectRecordExists(projectName: RawlsBillingProjectName): Boolean

  /**
    * Create a record for the specified project. Throws error if name
    * already exists or could not be otherwise created.
    *
    * @param projectName name of the project to use when creating a record
    * @return the created project record
    */
  def insertProjectRecord(projectName: RawlsBillingProjectName): TrialProject

  /**
    * Update the "verified" field for a specified project record. The "verified" field indicates whether
    * or not the associated billing project was created successfully in Google Cloud. Throws an error if
    * the record was not found or the record could not be updated.
    *
    * @param projectName name of the project record to update
    * @param verified verified value with which to update the project record
    * @return the updated project record
    */
  def setProjectRecordVerified(projectName: RawlsBillingProjectName, verified: Boolean, status: CreationStatus): TrialProject

  /**
    * Associates the next-available project record with a specified user. Definition of "next available"
    * is deferred to impl classes. Throws an error if no project records are available, or if the project
    * record could not be updated.
    *
    * @param userInfo the user (email and subjectid) with which to update the project record.
    * @return the updated project record
    */
  def claimProjectRecord(userInfo: WorkbenchUserInfo): TrialProject

  /**
    * Returns a list of project records in the pool that are unverified.
    * @return list of project records in the pool that are unverified.
    */
  def listUnverifiedProjects: Seq[TrialProject]

  /**
    * Returns a count of available project records. Definition of "available" is deferred to impl classes.
    * @return count of available project records.
    */
  def countAvailableProjects: Long

  /**
    * Returns a list of project records that have associated users.
    * @return list of project records that have associated users.
    */
  def projectReport: Seq[TrialProject] // return a list of projects-to-users

}
