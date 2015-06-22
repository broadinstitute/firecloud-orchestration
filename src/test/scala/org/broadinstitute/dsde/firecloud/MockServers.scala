package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.model.MethodEntity
import org.broadinstitute.dsde.firecloud.model.MethodEntityJsonProtocol._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.{Cookie, Header}
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse._
import spray.json._

/**
 * Represents all possible results that can be returned from the Methods Service
 */
object MockServers {

  val header = new Header("Content-Type", "application/json")
  var mockMethodsServer: ClientAndServer = _

  if (mockMethodsServer == null) {
    mockMethodsServer = startClientAndServer(8989)
  }
  
  def stopServers(): Unit = {
    mockMethodsServer.stop()
  }

  def startServers(): Unit = {

    MockServers.mockMethodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/methods")
          .withCookies(
            new Cookie("iPlanetDirectoryPro", ".*")
          )
      ).respond(
        response()
          .withHeaders(header)
          .withBody(
            mockMethodEntities().toJson.prettyPrint
          )
          .withStatusCode(200)
      )

    MockServers.mockMethodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/methods")
      ).respond(
        response()
          .withHeaders(header)
          .withBody("Invalid authentication token, please log in.")
          .withStatusCode(302)
      )

  }

  /****** Utility methods ******/

  def mockMethodEntities(): List[MethodEntity] = {
    List.tabulate(randomInt())(
      n =>
        MethodEntity(
          namespace = Some(randomAlpha()),
          name = Some(randomAlpha()),
          snapshotId = Some(randomInt()),
          synopsis = Some(randomAlpha()),
          documentation = Some(randomAlpha()),
          owner = Some(randomAlpha()),
          createDate = Some(randomAlpha()),
          payload = Some(randomAlpha()),
          url = Some(randomAlpha()),
          entityType = Some(randomAlpha())
        )
    )
  }

  def randomInt(): Int = {
    scala.util.Random.nextInt(9) + 1
  }

  def randomAlpha(): String = {
    val chars = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
    randomStringFromCharList(randomInt(), chars)
  }

  def randomStringFromCharList(length: Int, chars: Seq[Char]): String = {
    val sb = new StringBuilder
    for (i <- 1 to length) {
      val randomNum = util.Random.nextInt(chars.length)
      sb.append(chars(randomNum))
    }
    sb.toString()
  }

}
