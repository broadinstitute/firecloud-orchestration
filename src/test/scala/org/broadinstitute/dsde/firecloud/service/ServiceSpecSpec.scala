package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.HttpMethods._

final class ServiceSpecSpec extends ServiceSpec {
  "allHttpMethodsExcept() works" in {
    allHttpMethodsExcept(GET) should be(Seq(CONNECT, DELETE, HEAD, PATCH, POST, PUT, TRACE))
    allHttpMethodsExcept(DELETE, POST) should be(Seq(CONNECT, GET, HEAD, PATCH, PUT, TRACE))
  }
}
