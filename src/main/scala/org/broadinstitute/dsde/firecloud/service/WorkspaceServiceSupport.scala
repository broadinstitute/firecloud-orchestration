package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.Attributable._
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations._
import org.broadinstitute.dsde.firecloud.model._

/**
  * Created by Matt Putnam on 11/1/16.
  */
trait WorkspaceServiceSupport extends AttributeSupport {
  def generateAttributeOperations(existingAttrs: AttributeMap, newAttrs: AttributeMap): Seq[AttributeUpdateOperation] = {
    generateAttributeOperations(existingAttrs, newAttrs, _.namespace != AttributeName.libraryNamespace)
  }
}
