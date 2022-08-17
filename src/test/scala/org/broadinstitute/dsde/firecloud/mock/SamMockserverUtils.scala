package org.broadinstitute.dsde.firecloud.mock

import akka.http.scaladsl.model.StatusCodes.OK
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

trait SamMockserverUtils {

  /**
    * configures a Sam mockserver instance to return an enabled user from
    * the /register/user/v2/self/info api.
    *
    * @param samMockserver the Sam mockserver to configure
    */
  def returnEnabledUser(samMockserver: ClientAndServer): Unit = {
    samMockserver
      .when(request
        .withMethod("GET")
        .withPath("/register/user/v2/self/info"))
      .respond(response()
          .withHeaders(MockUtils.header).withBody(
          """{
            |  "adminEnabled": true,
            |  "enabled": true,
            |  "userEmail": "enabled@nowhere.com",
            |  "userSubjectId": "enabled-id"
            |}""".stripMargin).withStatusCode(OK.intValue))
  }

}
