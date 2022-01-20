package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.rawls.model.ErrorReport

class FireCloudException(message: String = null, cause: Throwable = null) extends Exception(message, cause)

class FireCloudExceptionWithErrorReport(val errorReport: ErrorReport) extends FireCloudException(errorReport.toString)
