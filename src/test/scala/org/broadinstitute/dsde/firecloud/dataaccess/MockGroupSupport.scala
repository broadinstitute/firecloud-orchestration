package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}

/**
  * Created by mbemis on 4/25/18.
  */
trait MockGroupSupport {

  var groups: Map[WorkbenchGroupName, Set[WorkbenchEmail]] = Map(
    WorkbenchGroupName("TCGA-dbGaP-Authorized") -> Set(WorkbenchEmail("tcga-linked"), WorkbenchEmail("tcga-linked-no-expire-date"), WorkbenchEmail("tcga-linked-expired"), WorkbenchEmail("tcga-linked-user-invalid-expire-date"), WorkbenchEmail("tcga-and-target-linked"), WorkbenchEmail("tcga-and-target-linked-expired")),
    WorkbenchGroupName("TARGET-dbGaP-Authorized") -> Set(WorkbenchEmail("target-linked"), WorkbenchEmail("target-linked-expired"), WorkbenchEmail("tcga-and-target-linked"), WorkbenchEmail("tcga-and-target-linked-expired"))
  )

}
