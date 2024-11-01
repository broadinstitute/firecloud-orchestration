package org.broadinstitute.dsde.firecloud.filematch

import spray.json.DefaultJsonProtocol.jsonFormat4
import spray.json.RootJsonFormat
import spray.json.DefaultJsonProtocol._

case class FileMatchingOptions(prefix: String,
                               read1Name: Option[String] = None,
                               read2Name: Option[String] = None,
                               recursive: Option[Boolean] = None
)

object FileMatchingOptionsFormat {
  implicit val fileMatchingOptionsFormat: RootJsonFormat[FileMatchingOptions] = jsonFormat4(FileMatchingOptions)
}
