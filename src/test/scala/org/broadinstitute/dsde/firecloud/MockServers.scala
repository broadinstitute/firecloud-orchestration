package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat
import java.util.Date

import org.broadinstitute.dsde.firecloud.model.{WorkspaceEntity, MethodEntity}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.{Cookie, Header}
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.http.StatusCodes._
import spray.json._
import DefaultJsonProtocol._
import org.mockserver.model.HttpCallback._

/**
 * Represents all possible results that can be returned from the Methods Service
 */
object MockServers {

  val isoDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")
  val cookie = new Cookie("iPlanetDirectoryPro", ".*")
  val header = new Header("Content-Type", "application/json")
  val mockMethodEntities: List[MethodEntity] = {
    List.tabulate(randomPositiveInt())(
      n =>
        MethodEntity(
          namespace = Some(randomAlpha()),
          name = Some(randomAlpha()),
          snapshotId = Some(randomPositiveInt()),
          synopsis = Some(randomAlpha()),
          documentation = Some(randomAlpha()),
          owner = Some(randomAlpha()),
          createDate = Some(isoDate()),
          payload = Some(randomAlpha()),
          url = Some(randomAlpha()),
          entityType = Some(randomAlpha())
        )
    )
  }
  def createMockWorkspace(): WorkspaceEntity = {
    WorkspaceEntity(
      name = Some(randomAlpha()),
      namespace = Some(randomAlpha()),
      createdDate = Some(isoDate()),
      createdBy = Some(randomAlpha()),
      attributes = Some(Map.empty)
    )
  }
  val mockWorkspaceEntities: List[WorkspaceEntity] = {
    List.tabulate(randomPositiveInt())(n => createMockWorkspace())
  }

  var methodsServer: ClientAndServer = _
  var workspaceServer: ClientAndServer = _

  def stopMethodsServer(): Unit = {
    methodsServer.stop()
  }

  def stopWorkspaceServer(): Unit = {
    workspaceServer.stop()
  }

  def startMethodsServer(): Unit = {
    methodsServer = startClientAndServer(8989)

    MockServers.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/methods")
          .withCookies(cookie)
      ).respond(
        response()
          .withHeaders(header)
          .withBody(
            mockMethodEntities.toJson.prettyPrint
          )
          .withStatusCode(OK.intValue)
      )

    MockServers.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/methods")
      ).respond(
        response()
          .withHeaders(header)
          .withBody("Invalid authentication token, please log in.")
          .withStatusCode(Found.intValue)
      )

  }

  def startWorkspaceServer(): Unit = {
    workspaceServer = startClientAndServer(8990)

    MockServers.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/workspaces")
          .withCookies(cookie)
      ).callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.ValidWorkspaceCallback")
      )

    MockServers.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/workspaces")
      ).respond(
        response()
          .withHeaders(header)
          .withBody("Authentication is possible but has failed or not yet been provided.")
          .withStatusCode(Unauthorized.intValue)
      )

    MockServers.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/workspaces")
          .withCookies(cookie)
      ).respond(
        response()
          .withHeaders(header)
          .withBody(
            mockWorkspaceEntities.toJson.prettyPrint
          )
          .withStatusCode(OK.intValue)
      )
  }

  /****** Utilities ******/
  
  def randomPositiveInt(): Int = {
    scala.util.Random.nextInt(9) + 1
  }

  def randomAlpha(): String = {
    val chars = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
    randomStringFromCharList(randomPositiveInt(), chars)
  }

  def randomStringFromCharList(length: Int, chars: Seq[Char]): String = {
    val sb = new StringBuilder
    for (i <- 1 to length) {
      val randomNum = util.Random.nextInt(chars.length)
      sb.append(chars(randomNum))
    }
    sb.toString()
  }

  def isoDate(): String = {
    isoDateFormat.format(new Date())
  }
}
