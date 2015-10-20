package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ErrorReport
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, Configuration, Method}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.mockserver.model.HttpResponse._
import spray.http.StatusCode
import spray.http.StatusCodes._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.json._
import DefaultJsonProtocol._

object MockMethodsServer {




  val methodsServerPort = 8989

  /****** Mock Data ******/

  def generateRandomRoles():List[String] =
  {
    //each of the 5 individual roles
    //has a 1/2 probability of showing up
    val randomNum=scala.util.Random.nextInt(2)
    val aList=List[String]()
    if(randomNum %2==0) {
      aList :+ "Read"
    }
    val randomNum2=scala.util.Random.nextInt(2)
    if(randomNum2 %2==0 )
    {
      aList :+ "Write"
    }
    val randomNum3=scala.util.Random.nextInt(2)
    if(randomNum3 %2==0)
    {
      aList :+ "Create"
    }
    val randomNum4=scala.util.Random.nextInt(2)
    if(randomNum4 %2==0)
    {
      aList :+ "Redact"
    }
    val randomNum5=scala.util.Random.nextInt(2)
    if(randomNum5 %2==0)
    {
      aList :+ "Manage"
    }
    return aList
  }

 val mockPermissions: List[AgoraPermission] = {
    List.tabulate(randomPositiveInt()-1)(
    n=>
      AgoraPermission(
        user=Some(randomAlpha()),
        roles=Some(generateRandomRoles())
      )
    )
  }

  val mockMethods: List[Method] = {
    List.tabulate(randomPositiveInt())(
      n =>
        Method(
          namespace = Some(randomAlpha()),
          name = Some(randomAlpha()),
          snapshotId = Some(randomPositiveInt()),
          synopsis = Some(randomAlpha()),
          owner = Some(randomAlpha()),
          createDate = Some(isoDate()),
          url = Some(randomAlpha()),
          entityType = Some(randomAlpha())
        )
    )
  }
  val mockConfigurations: List[Configuration] = {
    List.tabulate(randomPositiveInt())(
      n =>
        Configuration(
          namespace = Some(randomAlpha()),
          name = Some(randomAlpha()),
          snapshotId = Some(randomPositiveInt()),
          synopsis = Some(randomAlpha()),
          documentation = Some(randomAlpha()),
          owner = Some(randomAlpha()),
          payload = Some(randomAlpha()),
          excludedField = Some(randomAlpha()),
          includedField = Some(randomAlpha())
        )
    )
  }

  def agoraErrorReport(statusCode: StatusCode) =
    ErrorReport("Agora", "dummy text", Option(statusCode), Seq(), Seq())

  /****** Server ******/

  var methodsServer: ClientAndServer = _

  def stopMethodsServer(): Unit = {
    methodsServer.stop()
  }

  def startMethodsServer(): Unit = {

    methodsServer = startClientAndServer(methodsServerPort)

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/methods")
          .withHeader(authHeader)
      ).respond(
        response()
          .withHeaders(header)
          .withBody(
            mockMethods.toJson.prettyPrint
          )
          .withStatusCode(OK.intValue)
      )

    MockMethodsServer.methodsServer
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

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/methods")
      ).respond(
      response()
        .withStatusCode(MethodNotAllowed.intValue)
        .withHeader(header)
        .withBody(agoraErrorReport(MethodNotAllowed).toJson.compactPrint)
    )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("PUT")
          .withPath("/methods")
      ).respond(
      response()
        .withStatusCode(MethodNotAllowed.intValue)
        .withHeader(header)
        .withBody(agoraErrorReport(MethodNotAllowed).toJson.compactPrint)
    )

    MockMethodsServer.methodsServer
    .when(
      request()
        .withMethod("GET")
        .withPath("/configurations/%s/%s/%s/permissions"))
      .respond(
        response()
          .withStatusCode(200)
          .withHeader(header)
          .withBody(mockPermissions.toJson.compactPrint))

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/methods/%s/%s/%s/permissions"))
      .respond(
        response()
          .withStatusCode(200)
          .withHeader(header)
          .withBody(mockPermissions.toJson.compactPrint))

    //POST returns the same as GET, but the data returned
    //in the POST in real Agora is essentially an echo
    //of what was sent which
    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/configurations/%s/%s/%s/permissions"))
      .respond(
        response()
          .withStatusCode(200)
          .withHeader(header)
          .withBody(mockPermissions.toJson.compactPrint))

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/methods/%s/%s/%s/permissions"))
      .respond(
        response()
          .withStatusCode(200)
          .withHeader(header)
          .withBody(mockPermissions.toJson.compactPrint))



    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/configurations")
          .withHeader(authHeader)
      ).respond(
        response()
          .withHeaders(header)
          .withBody(
            mockConfigurations.toJson.prettyPrint
          )
          .withStatusCode(OK.intValue)
      )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/configurations")
      ).respond(
        response()
          .withHeaders(header)
          .withBody("Invalid authentication token, please log in.")
          .withStatusCode(Found.intValue)
      )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/configurations")
      ).respond(
      response()
        .withStatusCode(MethodNotAllowed.intValue)
        .withHeader(header)
        .withBody(agoraErrorReport(MethodNotAllowed).toJson.compactPrint)
    )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("PUT")
          .withPath("/configurations")
      ).respond(
      response()
        .withStatusCode(MethodNotAllowed.intValue)
        .withHeader(header)
        .withBody(agoraErrorReport(MethodNotAllowed).toJson.compactPrint)
    )

  }

}
