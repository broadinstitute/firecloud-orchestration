package org.broadinstitute.dsde.firecloud.model

case class Entity  (
  wsNamespace: Option[String] = None,
  wsName: Option[String] = None,
  entityType: Option[String] = None,
  entityName: Option[String] = None,
  attributes: Option[Map[String, String]] = None)