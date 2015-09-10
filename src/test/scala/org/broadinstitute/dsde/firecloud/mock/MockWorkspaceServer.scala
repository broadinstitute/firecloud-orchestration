package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType
import GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpCallback._
import org.mockserver.model.HttpRequest._
import org.mockserver.model.HttpResponse._
import spray.http.FormData
import spray.http.StatusCodes._
import spray.json.DefaultJsonProtocol._
import spray.json._

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

  val mockWorkspaceACL: List[Map[String, String]] = List(
    Map("userId" -> randomAlpha(), "accessLevel" -> randomAlpha()),
    Map("userId" -> randomAlpha(), "accessLevel" -> randomAlpha())
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

  val mockValidId = randomPositiveInt()

  val mockValidSubmission = SubmissionIngest(
    methodConfigurationNamespace = Option(randomAlpha()),
    methodConfigurationName = Option(randomAlpha()),
    entityType = Option(randomAlpha()),
    entityName = Option(randomAlpha()),
    expression = Option(randomAlpha())    
  ) 
  
  val mockInvalidSubmission = SubmissionIngest(
    methodConfigurationNamespace = Option.empty,
    methodConfigurationName = Option.empty,
    entityType = Option.empty,
    entityName = Option.empty,
    expression = Option.empty
  )

  val entitiesWithTypeBasePath = "/workspaces/broad-dsde-dev/alexb_test_submission/"

  var workspaceServer: ClientAndServer = _

  def stopWorkspaceServer(): Unit = {
    workspaceServer.stop()
  }

  def startWorkspaceServer(): Unit = {
    workspaceServer = startClientAndServer(workspaceServerPort)

    // Submissions responses

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/workspaces/%s/%s/submissions"
            .format(mockValidWorkspace.namespace.get, mockValidWorkspace.name.get))
          .withHeader(authHeader))
      .callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidSubmissionCallback")
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/workspaces/%s/%s/submissions"
          .format(mockValidWorkspace.namespace.get, mockValidWorkspace.name.get)))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(Found.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"/workspaces/%s/%s/submissions"
          .format(mockValidWorkspace.namespace.get, mockValidWorkspace.name.get))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"/workspaces/%s/%s/submissions/%s"
            .format(mockValidWorkspace.namespace.get, mockValidWorkspace.name.get, mockValidId))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
          .withBody(mockValidSubmission.toJson.prettyPrint)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("DELETE")
          .withPath(s"/workspaces/%s/%s/submissions/%s"
          .format(mockValidWorkspace.namespace.get, mockValidWorkspace.name.get, mockValidId))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(204)
      )


    // workspace-level responses

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/workspaces")
          .withHeader(authHeader))
      .callback(
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
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withBody(mockWorkspaces.toJson.prettyPrint)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"/workspaces/%s/%s/acl"
            .format(mockValidWorkspace.namespace.get, mockValidWorkspace.name.get))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withBody(mockWorkspaceACL.toJson.prettyPrint)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("PATCH")
          .withPath(s"/workspaces/%s/%s/acl"
            .format(mockValidWorkspace.namespace.get, mockValidWorkspace.name.get))
          .withBody(mockWorkspaceACL.toJson.prettyPrint)
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )

    // Method Configuration responses

    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("GET")
          .withPath(s"/workspaces/%s/%s/methodconfigs"
            .format(mockInvalidWorkspace.namespace.get, mockInvalidWorkspace.name.get))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(NotFound.intValue))

    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("GET")
          .withPath(s"/workspaces/%s/%s/methodconfigs".
            format(mockValidWorkspace.namespace.get, mockValidWorkspace.name.get))
          .withHeader(authHeader))
      .respond(
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
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue))

    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("GET")
          .withPath(s"/workspaces/%s/%s/methodconfigs/%s/%s".
          format(
            mockValidWorkspace.namespace.get,
            mockValidWorkspace.name.get,
            mockValidWorkspace.namespace.get,
            mockValidWorkspace.name.get))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withBody(mockMethodConfigs.head.toJson.prettyPrint)
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

    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("DELETE")
          .withPath(s"/workspaces/%s/%s/methodconfigs/%s/%s".
          format(
            mockValidWorkspace.namespace.get,
            mockValidWorkspace.name.get,
            mockValidWorkspace.namespace.get,
            mockValidWorkspace.name.get))
      ).
      respond(
        response()
          .withStatusCode(NoContent.intValue))

    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("DELETE")
          .withPath(s"/workspaces/%s/%s/methodconfigs/%s/%s".
          format(
            mockInvalidWorkspace.namespace.get,
            mockInvalidWorkspace.name.get,
            mockInvalidWorkspace.namespace.get,
            mockInvalidWorkspace.name.get))
      ).
      respond(
        response()
          .withStatusCode(NotFound.intValue))

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("PUT")
          .withPath(s"/workspaces/${mockValidWorkspace.namespace.get}/${mockValidWorkspace.name.get}/methodconfigs")
          .withBody("")
          .withHeader(authHeader))
      .respond(
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
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(Created.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(entitiesWithTypeBasePath + "entities")
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withBody(List("participant", "sample", "Pair", "sampleset").toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(entitiesWithTypeBasePath + "entities/participant")
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withBody(List(EntityWithType("participant_01", "participant", Option.empty), EntityWithType("participant_02", "participant", Option.empty)).toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(entitiesWithTypeBasePath + "entities/sample")
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withBody(List(EntityWithType("sample_01", "sample", Option.empty), EntityWithType("sample_02", "sample", Option.empty)).toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(entitiesWithTypeBasePath + "entities/Pair")
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withBody(List(EntityWithType("pair_01", "Pair", Option.empty)).toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(entitiesWithTypeBasePath + "entities/sampleset")
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withBody(List(EntityWithType("sampleset_01", "sampleset", Option.empty), EntityWithType("sampleset_02", "sampleset", Option.empty)).toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/workspaces/${mockPairValid.wsNamespace.get}/${mockPairValid.wsName.get}/entities")
          .withBody(mockPairValid.toJson.compactPrint)
          .withHeader(authHeader))
      .respond(
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
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(Conflict.intValue)
      )

    MockWorkspaceServer.workspaceServer.
      when(
        request()
          .withMethod("POST")
          .withPath("/methodconfigs/copyFromMethodRepo")
          .withHeader(authHeader))
      .callback(
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
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(NoContent.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"/workspaces/${mockValidWorkspace.namespace.get}/${mockValidWorkspace.name.get}/entities/batchUpdate")
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(NoContent.intValue)
      )
  }

}
