package org.broadinstitute.dsde.firecloud.model

/**
  * Created by ahaessly on 11/2/16.
  */

  case class LibrarySearchParams(
    searchTerm: String,
    from: Option[Int] = Some(0),
    size: Option[Int] = Some(10))



