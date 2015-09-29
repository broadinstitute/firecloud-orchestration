package org.broadinstitute.dsde.firecloud.model

object MethodRepository {

  case class Configuration(
    namespace: Option[String] = None,
    name: Option[String] = None,
    snapshotId: Option[Int] = None,
    synopsis: Option[String] = None,
    documentation: Option[String] = None,
    owner: Option[String] = None,
    payload: Option[String] = None,
    excludedField: Option[String] = None,
    includedField: Option[String] = None)

  case class Method(
    namespace: Option[String] = None,
    name: Option[String] = None,
    snapshotId: Option[Int] = None,
    synopsis: Option[String] = None,
    owner: Option[String] = None,
    createDate: Option[String] = None,
    url: Option[String] = None,
    entityType: Option[String] = None)

}
