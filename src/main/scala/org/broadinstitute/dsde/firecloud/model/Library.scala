package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.json._
import spray.json.DefaultJsonProtocol._

/**
  * Created by ahaessly on 11/2/16.
  */

  case class LibrarySearchParams(
    searchTerm: Option[String],
    from: Int = 0,
    size: Int = 10)

  case class LibrarySearchResponse(
    searchTerm: Option[String],
    from: Int,
    size: Int,
    total: Int,
    results: Array[JsValue]) {
    def this (params: LibrarySearchParams, total: Int, results: Array[JsValue]) =
      this(params.searchTerm, params.from, params.size, total, results)
  }
