package org.broadinstitute.dsde.firecloud.trial

import org.scalatest.FreeSpec

class ProjectNamerSpec extends FreeSpec {

  "ProjectNamer" - {
    "should always be 6-30 chars, start with a letter, and lower case alphanumeric or hyphen" in {
      // project namer generates random names, so loop over the checks. This test can generate false positives if the
      // randomization is truly skewed AND the logic being tested is incorrect.
      1 to 5000 foreach { _ =>
        val name = ProjectNamer.randomName
        assert(name.length >= 6, s"'$name' is not at least 6 chars")
        assert(name.length <= 30, s"'$name' is not 30 chars or less")
        assert(name.matches("[a-z][a-z0-9\\-]+"), s"'$name' does not start with a letter or is not alphanumeric or hyphen")
      }
    }
  }
}
