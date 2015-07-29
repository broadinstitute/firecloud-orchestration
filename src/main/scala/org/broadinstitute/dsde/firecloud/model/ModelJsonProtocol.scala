package org.broadinstitute.dsde.firecloud.model

import spray.json.DefaultJsonProtocol._

object ModelJsonProtocol {

  implicit val impMethod = jsonFormat8(MethodRepository.Method)
  implicit val impConfiguration = jsonFormat9(MethodRepository.Configuration)

  implicit val impWorkspaceName = jsonFormat2(WorkspaceName)
  implicit val impWorkspaceEntity = jsonFormat5(WorkspaceEntity)

  implicit val impEntity = jsonFormat5(Entity)
  implicit val impEntityCreateResult = jsonFormat4(EntityCreateResult)

  implicit val impMethodConfiguration = jsonFormat9(MethodConfiguration)

  implicit val impDestination = jsonFormat3(Destination)
  implicit val impMethodConfigurationCopy = jsonFormat4(MethodConfigurationCopy)

}
