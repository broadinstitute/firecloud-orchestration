package org.broadinstitute.dsde.test

import org.broadinstitute.dsde.workbench.config.CommonConfig

object OrchConfig extends CommonConfig {

  object Users extends CommonUsers {
    val tcgaJsonWebTokenKey = usersConfig.getString("tcgaJsonWebTokenKey")
    val targetJsonWebTokenKey = usersConfig.getString("targetJsonWebTokenKey")
    val targetAndTcgaJsonWebTokenKey = usersConfig.getString("targetAndTcgaJsonWebTokenKey")
    val genericJsonWebTokenKey = usersConfig.getString("genericJsonWebTokenKey")
    val tempSubjectId = usersConfig.getString("tempSubjectId")
  }

}
