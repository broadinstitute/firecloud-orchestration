package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.rawls.model.Attribute
import spray.json._

/**
 * Created by tsharpe on 7/28/15.
 */

case class EntityUpdateDefinition( name: String,
                                   entityType: String,
                                   operations: Seq[Map[String, Attribute]] )
