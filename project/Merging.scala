import sbtassembly.{MergeStrategy, PathList}

object Merging {
  def customMergeStrategy(oldStrategy: (String) => MergeStrategy):(String => MergeStrategy) = {
    case x if x.endsWith("io.netty.versions.properties") => MergeStrategy.discard
    case x if x.contains("native-image/io.netty") => MergeStrategy.first

    // TODO: we no longer target Java 8, reassess this:
    // we target Java 8, which does not use module-info.class. Some dependencies (Jackson) cause assembly problems on module-info
    case x if x.endsWith("module-info.class") => MergeStrategy.discard

    case x if x.contains("javax/activation") => MergeStrategy.first
    case x if x.contains("javax/annotation") => MergeStrategy.first

    case x if x.endsWith("kotlin-stdlib.kotlin_module") => MergeStrategy.first
    case x if x.endsWith("kotlin-stdlib-common.kotlin_module") => MergeStrategy.first
    case x if x.endsWith("arrow-git.properties") => MergeStrategy.concat

    // For the following error:
    // Error: (assembly) deduplicate: different file contents found in the following:
    // Error: /home/sbtuser/.cache/coursier/v1/https/repo1.maven.org/maven2/com/google/protobuf/protobuf-java/3.19.4/protobuf-java-3.19.4.jar:google/protobuf/struct.proto
    // Error: /home/sbtuser/.cache/coursier/v1/https/repo1.maven.org/maven2/com/typesafe/akka/akka-protobuf-v3_2.13/2.6.19/akka-protobuf-v3_2.13-2.6.19.jar:google/protobuf/struct.proto
    case PathList("google", "protobuf", _ @ _*) => MergeStrategy.first
    case x => oldStrategy(x)
  }
}
