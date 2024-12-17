package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.parboiled.common.FileUtils

import scala.util.Try

object FireCloudDirectiveUtils {
  def encodeUri(path: String): String = {
    val pattern = """(https|http)://([^/\r\n]+?)(:\d+)?(/[^\r\n]*)?""".r

    def toUri(url: String) = url match {
      case pattern(theScheme, theHost, thePort, thePath) =>
        val p: Int = Try(thePort.replace(":", "").toInt).toOption.getOrElse(0)
        Uri.from(scheme = theScheme, port = p, host = theHost, path = thePath)
    }
    toUri(path).toString
  }
}

trait FireCloudDirectives extends Directives with RequestBuilding with RestJsonClient {

  def encodeUri(path: String): String = FireCloudDirectiveUtils.encodeUri(path)

  def withResourceFileContents(path: String)(innerRoute: String => Route): Route =
    innerRoute(FileUtils.readAllTextFromResource(path))

}
