package org.broadinstitute.dsde.firecloud.service

import spray.http.HttpMethods._

final class ServiceSpecSpec extends ServiceSpec {
  "allHttpMethodsExcept() works" in {
    allHttpMethodsExcept(GET) should be(Seq(CONNECT, DELETE, HEAD, OPTIONS, PATCH, POST, PUT, TRACE))
    allHttpMethodsExcept(DELETE, POST) should be(Seq(CONNECT, GET, HEAD, OPTIONS, PATCH, PUT, TRACE))
  }
}
