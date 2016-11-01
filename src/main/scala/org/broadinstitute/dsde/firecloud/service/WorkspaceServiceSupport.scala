package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.Attributable._
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations._

/**
  * Created by Matt Putnam on 11/1/16.
  */
trait WorkspaceServiceSupport {
  def generateAttributeOperations(existingAttrs: AttributeMap, newAttrs: AttributeMap): Seq[AttributeUpdateOperation] = {
    // in this method, ONLY work with non-"library:" attributes
    val oldKeys = existingAttrs.keySet.filter(_.namespace != AttributeName.libraryNamespace)
    val newFields = newAttrs.seq.filter(_._1.namespace != AttributeName.libraryNamespace)

    // remove any attributes that currently exist on the workspace, but are not in the user's packet
    // for any array attributes, we remove them and recreate them entirely. Add the array attrs.
    val keysToRemove: Set[AttributeName] = oldKeys.diff(newFields.keySet) ++ newFields.filter(_._2.isInstanceOf[AttributeList[_]]).keySet
    val removeOperations = keysToRemove.map(RemoveAttribute).toSeq

    val updateOperations = newFields.toSeq flatMap {
      case (key, value:AttributeValue) => Seq(AddUpdateAttribute(key, value))
      case (key, value:AttributeEntityReference) => Seq(AddUpdateAttribute(key, value))

      case (key, value:AttributeList[Attribute @unchecked]) => value.list.map(x => AddListMember(key, x))
    }

    // handle removals before upserts
    removeOperations ++ updateOperations
  }
}
