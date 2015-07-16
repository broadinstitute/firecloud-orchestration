package org.broadinstitute.dsde.firecloud.model

import spray.json.DefaultJsonProtocol._

object ModelJsonProtocol {

  implicit val impMethodEntity = jsonFormat10(MethodEntity)

  implicit val impWorkspaceIngest = jsonFormat2(WorkspaceIngest)
  implicit val impWorkspaceEntity = jsonFormat5(WorkspaceEntity)

  implicit val impEntityCreateResult = jsonFormat3(EntityCreateResult)

}
