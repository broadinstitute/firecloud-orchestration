import Settings._

lazy val orchIntegration = project.in(file("."))
  .settings(rootSettings:_*)

version := "1.0"
