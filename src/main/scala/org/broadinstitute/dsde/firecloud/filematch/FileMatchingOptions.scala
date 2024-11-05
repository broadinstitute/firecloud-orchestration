package org.broadinstitute.dsde.firecloud.filematch

import spray.json.DefaultJsonProtocol.jsonFormat4
import spray.json.RootJsonFormat
import spray.json.DefaultJsonProtocol._

/**
  * Request payload, specified by end users, to control file-matching functionality
  * @param prefix bucket prefix in which to list files
  * @param read1Name name for the "read1" column
  * @param read2Name name for the "read2" column
  * @param recursive should bucket-listing be recursive?
  */
case class FileMatchingOptions(prefix: String,
                               read1Name: Option[String] = None,
                               read2Name: Option[String] = None,
                               recursive: Option[Boolean] = None
)

object FileMatchingOptionsFormat {
  implicit val fileMatchingOptionsFormat: RootJsonFormat[FileMatchingOptions] = jsonFormat4(FileMatchingOptions)
}
