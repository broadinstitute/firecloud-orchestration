package org.broadinstitute.dsde.firecloud.model

import spray.json.DefaultJsonProtocol._

object ModelJsonProtocol {

  implicit val impMethod = jsonFormat8(MethodRepository.Method)
  implicit val impConfiguration = jsonFormat9(MethodRepository.Configuration)

  implicit val impWorkspaceIngest = jsonFormat2(WorkspaceIngest)
  implicit val impWorkspaceEntity = jsonFormat5(WorkspaceEntity)

  implicit val impEntity = jsonFormat5(Entity)
  implicit val impEntityCreateResult = jsonFormat4(EntityCreateResult)

  implicit val impMethodConfiguration = jsonFormat9(MethodConfiguration)

  implicit val impEntityMetadata = jsonFormat3(EntityMetadata)
  implicit val impModelSchema = jsonFormat1(EntityModel)
}
