package org.broadinstitute.dsde.firecloud.model

import spray.json.JsObject

/**
  * Created by ahaessly on 11/2/16.
  */

  case class LibrarySearchParams(
    searchTerm: String,
    from: Int = 0,
    size: Int = 10)

  case class LibrarySearchResponse(
    total: Int,
    results: Array[JsObject])


