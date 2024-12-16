package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.utils.TsvFormatterBenchmark.Inputs
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}
import org.openjdk.jmh.infra.Blackhole

object TsvFormatterBenchmark {

  @State(Scope.Thread)
  class Inputs {
    val inputNoTab = "foo"
    val inputWithTab = "foo\tbar"
  }

}

class TsvFormatterBenchmark {

  @Benchmark
  def tsvSafeStringNoTab(blackHole: Blackhole, inputs: Inputs): String = {
    val result = TSVFormatter.tsvSafeString(inputs.inputNoTab)
    blackHole.consume(result)
    result
  }

  @Benchmark
  def tsvSafeStringWithTab(blackHole: Blackhole, inputs: Inputs): String = {
    val result = TSVFormatter.tsvSafeString(inputs.inputWithTab)
    blackHole.consume(result)
    result
  }

}
