package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap

case class Entity  (
  wsNamespace: Option[String] = None,
  wsName: Option[String] = None,
  entityType: Option[String] = None,
  entityName: Option[String] = None,
  attributes: Option[AttributeMap] = None)
