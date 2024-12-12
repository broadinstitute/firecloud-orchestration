package org.broadinstitute.dsde.firecloud.utils

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.infra.Blackhole

class TsvFormatterBenchmark {

  @Benchmark
  def tsvSafeStringNoTab(blackHole: Blackhole): String = {
    val result = TSVFormatter.tsvSafeString("foo")
    blackHole.consume(result)
    result
  }

  @Benchmark
  def tsvSafeStringWithTab(blackHole: Blackhole): String = {
    val result = TSVFormatter.tsvSafeString("foo\tbar")
    blackHole.consume(result)
    result
  }

}
