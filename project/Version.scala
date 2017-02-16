import sbt.Keys._
import sbt._

object Version {
  val versionRoot = "0.1"

  def getVersionString = {
    // Get the revision, or -1 (later will be bumped to zero)
    val versionRevision = {
      try {
        ("git rev-list --count HEAD" #|| "echo -1").!!.trim.toInt
      } catch {
        case e: Exception =>
          0
      }
    }

    // Set the suffix to None...
    val versionSuffix = {
      try {
        // ...except when there are no modifications...
        if ("git diff --quiet HEAD".! == 0) {
          // ...then set the suffix to the revision "dash" git hash
          Option(versionRevision + "-" + "git rev-parse --short HEAD".!!.trim)
        } else {
          None
        }
      } catch {
        case e: Exception =>
          None
      }
    }

    versionRoot + "-" + versionSuffix.getOrElse((versionRevision + 1) + "-SNAPSHOT")

  }

  val rootVersionSettings: Seq[Setting[_]] =
    Seq(version := getVersionString)

}
