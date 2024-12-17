package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.model.{FlexibleModelSchema, ModelSchema, SchemaTypes}
import org.broadinstitute.dsde.firecloud.utils.TsvFormatterBenchmark.{EntityData, Inputs}
import org.broadinstitute.dsde.rawls.model.{AttributeBoolean, AttributeName, AttributeNumber, AttributeString, Entity}
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}
import org.openjdk.jmh.infra.Blackhole

object TsvFormatterBenchmark {

  @State(Scope.Thread)
  class Inputs {
    val inputNoTab = "foo"
    val inputWithTab = "foo\tbar"
  }

  @State(Scope.Thread)
  class EntityData {
    val entityType: String = "sample"

    val model: ModelSchema = FlexibleModelSchema

    val headers: IndexedSeq[String] = IndexedSeq("sample_id", "col1", "col2", "fourth", "last")

    val entities: Seq[Entity] = Seq(
      Entity(
        "1",
        entityType,
        Map(
          AttributeName.withDefaultNS("col1") -> AttributeString("foo"),
          AttributeName.withDefaultNS("col2") -> AttributeBoolean(true),
          AttributeName.withDefaultNS("fourth") -> AttributeNumber(42),
          AttributeName.withDefaultNS("last") -> AttributeString("gs://some-bucket/somefile.ext")
        )
      ),
      Entity(
        "0005",
        entityType,
        Map(
          AttributeName.withDefaultNS("col1") -> AttributeString("bar"),
          AttributeName.withDefaultNS("col2") -> AttributeBoolean(false),
          AttributeName.withDefaultNS("fourth") -> AttributeNumber(99),
          AttributeName.withDefaultNS("last") -> AttributeString("gs://some-bucket/somefile2.ext")
        )
      ),
      Entity(
        "789",
        entityType,
        Map(
          AttributeName.withDefaultNS("col1") -> AttributeString("baz"),
          AttributeName.withDefaultNS("col2") -> AttributeBoolean(true),
          AttributeName.withDefaultNS("fourth") -> AttributeNumber(-123),
          AttributeName.withDefaultNS("last") -> AttributeString("gs://some-bucket/somefile3.ext")
        )
      )
    )
  }

}

class TsvFormatterBenchmark {

  @Benchmark
  def makeEntityRows(blackHole: Blackhole, entityData: EntityData): IndexedSeq[IndexedSeq[String]] = {
    val result =
      TSVFormatter.makeEntityRows(entityData.entityType, entityData.entities, entityData.headers)(entityData.model)
    blackHole.consume(result)
    result
  }
//
//  @Benchmark
//  def tsvSafeStringNoTab(blackHole: Blackhole, inputs: Inputs): String = {
//    val result = TSVFormatter.tsvSafeString(inputs.inputNoTab)
//    blackHole.consume(result)
//    result
//  }
//
//  @Benchmark
//  def tsvSafeStringWithTab(blackHole: Blackhole, inputs: Inputs): String = {
//    val result = TSVFormatter.tsvSafeString(inputs.inputWithTab)
//    blackHole.consume(result)
//    result
//  }

}
