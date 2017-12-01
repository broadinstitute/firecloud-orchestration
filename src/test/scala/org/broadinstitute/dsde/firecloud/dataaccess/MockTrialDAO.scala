package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses.CreationStatus
import org.broadinstitute.dsde.firecloud.model.Trial.TrialProject
import org.broadinstitute.dsde.firecloud.model.{Trial, WorkbenchUserInfo}
import org.broadinstitute.dsde.rawls.model.RawlsBillingProjectName
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MockTrialDAO extends TrialDAO {
  /**
    * Read the record for a specified project. Throws an error if record not found.
    *
    * @param projectName name of the project record to read.
    * @return the project record
    */
  override def getProjectRecord(projectName: RawlsBillingProjectName): Trial.TrialProject = throw new Exception("unit test intentional exception")


  /**
    * Check to see if the project record exists.
    *
    * @param projectName name of the project record to read.
    * @return whether or not the project record exists
    */
  override def projectRecordExists(projectName: RawlsBillingProjectName): Boolean = false

  /**
    * Create a record for the specified project. Throws error if name
    * already exists or could not be otherwise created.
    *
    * @param projectName name of the project to use when creating a record
    * @return the created project record
    */
  override def insertProjectRecord(projectName: RawlsBillingProjectName): Trial.TrialProject = throw new Exception("unit test intentional exception")

  /**
    * Update the "verified" field for a specified project record. The "verified" field indicates whether
    * or not the associated billing project was created successfully in Google Cloud. Throws an error if
    * the record was not found or the record could not be updated.
    *
    * @param projectName name of the project record to update
    * @param verified    verified value with which to update the project record
    * @return the updated project record
    */
  override def setProjectRecordVerified(projectName: RawlsBillingProjectName, verified: Boolean, status: CreationStatus): Trial.TrialProject = throw new Exception("unit test intentional exception")

  /**
    * Associates the next-available project record with a specified user. Definition of "next available"
    * is deferred to impl classes. Throws an error if no project records are available, or if the project
    * record could not be updated.
    *
    * @param userInfo the user (email and subjectid) with which to update the project record.
    * @return the updated project record
    */
  override def claimProjectRecord(userInfo: WorkbenchUserInfo): Trial.TrialProject = throw new Exception("unit test intentional exception")


  /**
    * Returns a list of project records in the pool that are unverified.
    *
    * @return list of project records in the pool that are unverified.
    */
  override def listUnverifiedProjects: Seq[TrialProject] = Seq.empty[TrialProject]

  /**
    * Returns a count of available project records. Definition of "available" is deferred to impl classes.
    *
    * @return count of available project records.
    */
  override def countAvailableProjects: Long = 0

  /**
    * Returns a list of project records that have associated users.
    *
    * @return list of project records that have associated users.
    */
override def projectReport: Seq[Trial.TrialProject] = Seq.empty[TrialProject]

  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))
}
