import sbtassembly.{MergeStrategy, PathList}

object Merging {
  def customMergeStrategy(oldStrategy: (String) => MergeStrategy):(String => MergeStrategy) = {
    case x if x.endsWith("io.netty.versions.properties") => MergeStrategy.discard
    // we target Java 8, which does not use module-info.class. Some dependencies (Jackson) cause assembly problems on module-info
    case x if x.endsWith("module-info.class") => MergeStrategy.discard
    case x if x.contains("javax/activation") => MergeStrategy.first
    case x => oldStrategy(x)
  }
}
