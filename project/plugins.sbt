addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.3")

addSbtPlugin("io.spray" % "sbt-revolver" % "0.7.2")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.2.7")

// latest coveralls (1.0.0) is *only* compatible with scoverage 1.0.4 or less: https://github.com/scoverage/sbt-coveralls/issues/49
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.1")

addSbtPlugin("com.github.tkawachi" % "sbt-repeat" % "0.1.0")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.2")