package org.broadinstitute.dsde.firecloud.model

import spray.json._

/**
 * Created by tsharpe on 7/28/15.
 */

case class EntityUpdateDefinition( name: String,
                                   entityType: String,
                                   operations: Seq[Map[String, Attribute]] )
