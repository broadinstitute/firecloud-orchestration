package org.broadinstitute.dsde.firecloud.model


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
    results: Array[Map[String,Object]]) {
    def this (params: LibrarySearchParams, total: Int, results: Array[Map[String,Object]]) =
      this(params.searchTerm, params.from, params.size, total, results)
  }
