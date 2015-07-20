package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat
import java.util.Date

import org.broadinstitute.dsde.firecloud.model.{EntityCreateResult, Entity, WorkspaceEntity, MethodEntity}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.{Cookie, Header}
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.http.FormData
import spray.http.StatusCodes._
import spray.json._
import DefaultJsonProtocol._
import org.mockserver.model.HttpCallback._

/**
 * Represents all possible results that can be returned from the Methods Service
 */
object MockServers {

  val methodsServerPort = 8989
  val workspaceServerPort = 8990

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

  val mockSampleValid = Entity(
    Some("namespace"),
    Some("name"),
    Some("sample"),
    Some("sample1"),
    Some(Map("a" -> "1", "b" -> "foo"))
  )

  val mockPairValid = Entity(
    Some("namespace"),
    Some("name"),
    Some("pair"),
    Some("pair1"),
    Some(Map("a" -> "1", "b" -> "foo"))
  )

  // "conflicts" with sample1 above (but different attributes so we can distinguish them)
  val mockSampleConflict = Entity(
    Some("namespace"),
    Some("name"),
    Some("sample"),
    Some("sample1"),
    Some(Map.empty)
  )

  // missing entity name
  val mockSampleMissingName = Entity(
    Some("namespace"),
    Some("name"),
    Some("sample"),
    None,
    Some(Map.empty)
  )

  val mockEmptyEntityFormData = FormData(Seq("entities" -> """[]"""))

  val mockNonEmptyEntityFormData = FormData(Seq("entities" -> Seq(
    MockServers.mockSampleValid,
    MockServers.mockPairValid,
    MockServers.mockSampleConflict,
    MockServers.mockSampleMissingName
  ).toJson.compactPrint))

  // the expected results of posting the entities from the form data above
  val mockNonEmptySuccesses = Seq(true, true, false, false)

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
    methodsServer = startClientAndServer(methodsServerPort)

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
    workspaceServer = startClientAndServer(workspaceServerPort)

    // workspace-level responses

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

    // entity-level responses

    MockServers.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/workspaces/${mockSampleValid.wsNamespace.get}/${mockSampleValid.wsName.get}/entities")
          .withBody(mockSampleValid.toJson.compactPrint)
          .withCookies(cookie)
      ).respond(
        response()
          .withHeaders(header)
          .withStatusCode(Created.intValue)
      )

    MockServers.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/workspaces/${mockPairValid.wsNamespace.get}/${mockPairValid.wsName.get}/entities")
          .withBody(mockPairValid.toJson.compactPrint)
          .withCookies(cookie)
      ).respond(
        response()
          .withHeaders(header)
          .withStatusCode(Created.intValue)
      )

    MockServers.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/workspaces/${mockSampleConflict.wsNamespace.get}/${mockSampleConflict.wsName.get}/entities")
          .withBody(mockSampleConflict.toJson.compactPrint)
          .withCookies(cookie)
      ).respond(
        response()
          .withHeaders(header)
          .withStatusCode(Conflict.intValue)
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
