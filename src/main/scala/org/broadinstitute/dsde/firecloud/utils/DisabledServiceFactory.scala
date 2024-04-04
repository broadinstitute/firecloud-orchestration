package org.broadinstitute.dsde.firecloud.utils

import java.lang.reflect.Proxy
import scala.reflect.{ClassTag, classTag}

object DisabledServiceFactory {

  /**
   * Create a new instance of a service that throws UnsupportedOperationException for all methods.
   * Implemented using a dynamic proxy.
   * @tparam T the type of the service, must be a trait
   * @return a new instance of the service that throws UnsupportedOperationException for all methods
   */
  def newDisabledService[T: ClassTag]: T =
    Proxy
      .newProxyInstance(
        classTag[T].runtimeClass.getClassLoader,
        Array(classTag[T].runtimeClass),
        (_, method, _) =>
          throw new UnsupportedOperationException(s"${method.toGenericString} is disabled.")
      )
      .asInstanceOf[T]
}
