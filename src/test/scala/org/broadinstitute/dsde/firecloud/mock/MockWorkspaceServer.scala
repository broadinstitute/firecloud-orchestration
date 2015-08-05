package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpCallback._
import org.mockserver.model.HttpRequest._
import org.mockserver.model.HttpResponse._
import spray.http.FormData
import spray.http.StatusCodes._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.json._
import DefaultJsonProtocol._

/**
 * Represents all possible results that can be returned from the Workspace Service
 */
object MockWorkspaceServer {

  val workspaceServerPort = 8990

  val mockWorkspaces:List[WorkspaceEntity] = {
    List.tabulate(randomPositiveInt())(
      n =>
        WorkspaceEntity(
          name = Some(randomAlpha()),
          namespace = Some(randomAlpha())
        )
    )
  }

  val mockValidWorkspace = WorkspaceEntity(
    Some("namespace"),
    Some("name")
  )

  val mockInvalidWorkspace = WorkspaceEntity(
    Some("invalidNamespace"),
    Some("invalidName")
  )

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
    MockWorkspaceServer.mockSampleValid,
    MockWorkspaceServer.mockPairValid,
    MockWorkspaceServer.mockSampleConflict,
    MockWorkspaceServer.mockSampleMissingName
  ).toJson.compactPrint))

  // the expected results of posting the entities from the form data above
  val mockNonEmptySuccesses = Seq(true, true, false, false)

  val mockMethodConfigs: List[MethodConfiguration] = {
    List.tabulate(2)(
      n =>
        MethodConfiguration(
          name = Some(randomAlpha()),
          namespace = Some(randomAlpha()),
          rootEntityType = Some(randomAlpha()),
          workspaceName = Some(Map.empty),
          methodRepoMethod = Some(Map.empty),
          methodRepoConfig = Some(Map.empty),
          outputs = Some(Map.empty),
          inputs = Some(Map.empty),
          prerequisites = Some(Map.empty)
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

  var workspaceServer: ClientAndServer = _

  def stopWorkspaceServer(): Unit = {
    workspaceServer.stop()
  }

  def startWorkspaceServer(): Unit = {
    workspaceServer = startClientAndServer(workspaceServerPort)

    // workspace-level responses

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/workspaces")
          .withCookies(cookie)
      ).callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidWorkspaceCallback")
      )

    MockWorkspaceServer.workspaceServer
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

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/workspaces")
          .withCookies(cookie)
      ).respond(
        response()
          .withHeaders(header)
          .withBody(mockWorkspaces.toJson.prettyPrint)
          .withStatusCode(OK.intValue)
      )

    // Method Configuration responses

    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("GET")
          .withPath(
            FireCloudConfig.
              Workspace.
              methodConfigsListPath.
              format(mockInvalidWorkspace.namespace.get, mockInvalidWorkspace.name.get))
          .withCookies(cookie)).
      respond(
        response()
          .withHeaders(header)
          .withStatusCode(NotFound.intValue))

    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("GET")
          .withPath(
            FireCloudConfig.
              Workspace.
              methodConfigsListPath.
              format(mockValidWorkspace.namespace.get, mockValidWorkspace.name.get))
          .withCookies(cookie)).
      respond(
        response()
          .withHeaders(header)
          .withBody(mockMethodConfigs.toJson.prettyPrint)
          .withStatusCode(OK.intValue))

    // Updating a method config
    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("PUT")
          .withPath(s"/workspaces/%s/%s/methodconfigs/%s/%s".
          format(
            mockValidWorkspace.namespace.get,
            mockValidWorkspace.name.get,
            mockValidWorkspace.namespace.get,
            mockValidWorkspace.name.get))
          .withBody(mockMethodConfigs.head.toJson.prettyPrint)
          .withCookies(cookie)).
      respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue))

    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("PUT")
          .withPath(s"/workspaces/%s/%s/methodconfigs/%s/%s".
          format(
            mockValidWorkspace.namespace.get,
            mockValidWorkspace.name.get,
            mockValidWorkspace.namespace.get,
            mockValidWorkspace.name.get))
          .withBody(mockMethodConfigs.head.toJson.prettyPrint)).
      respond(
        response()
          .withBody("Authentication is possible but has failed or not yet been provided.")
          .withStatusCode(Unauthorized.intValue))

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("PUT")
          .withPath(s"/workspaces/${mockValidWorkspace.namespace.get}/${mockValidWorkspace.name.get}/methodconfigs")
          .withBody("")
          .withCookies(cookie)
      ).respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )

    // entity-level responses

    MockWorkspaceServer.workspaceServer
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

    MockWorkspaceServer.workspaceServer
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

    MockWorkspaceServer.workspaceServer
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

    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("POST")
          .withPath("/methodconfigs/copyFromMethodRepo")
          .withCookies(cookie)
      ).callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidMethodConfigurationFromRepoCallback")
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/methodconfigs/copyFromMethodRepo")
      ).respond(
        response()
          .withHeaders(header)
          .withBody("Authentication is possible but has failed or not yet been provided.")
          .withStatusCode(Unauthorized.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/workspaces/${mockValidWorkspace.namespace.get}/${mockValidWorkspace.name.get}/entities/batchUpsert")
          .withCookies(cookie)
      ).respond(
        response()
          .withHeaders(header)
          .withStatusCode(NoContent.intValue)
      )
  }

}
