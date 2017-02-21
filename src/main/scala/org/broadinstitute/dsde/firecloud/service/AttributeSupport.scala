package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.rawls.model.Attributable._
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations._

/**
  * Created by putnam on 11/1/16.
  */
trait AttributeSupport {
  /**
    * given a set of existing attributes and a set of new attributes, calculate the attribute operations
    * that need to be performed
    */
  def generateAttributeOperations(existingAttrs: AttributeMap, newAttrs: AttributeMap, attributeFilter: AttributeName => Boolean): Seq[AttributeUpdateOperation] = {
    val oldKeys = existingAttrs.keySet.filter(attributeFilter)
    val newFields = newAttrs.filter { case (name: AttributeName, _) => attributeFilter(name) }

    // remove any attributes that currently exist on the workspace, but are not in the user's packet
    // for any array attributes, we remove them and recreate them entirely. Add the array attrs.
    val keysToRemove: Set[AttributeName] = oldKeys.diff(newFields.keySet) ++ newFields.filter( _._2.isInstanceOf[AttributeList[_]] ).keySet
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
