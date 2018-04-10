package org.broadinstitute.dsde.firecloud.metrics

import org.broadinstitute.dsde.firecloud.FireCloudException

class MetricsException(message:String, cause:Throwable = null) extends FireCloudException(message, cause)
