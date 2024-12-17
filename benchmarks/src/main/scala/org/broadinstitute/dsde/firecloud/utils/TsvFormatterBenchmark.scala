package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.model.{FlexibleModelSchema, ModelSchema}
import org.broadinstitute.dsde.firecloud.utils.TsvFormatterBenchmark.EntityData
import org.broadinstitute.dsde.rawls.model._
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}
import org.openjdk.jmh.infra.Blackhole

object TsvFormatterBenchmark {

  @State(Scope.Thread)
  class EntityData {
    val entityType: String = "sample"

    val model: ModelSchema = FlexibleModelSchema

    val headers: List[String] = List("sample_id", "col1", "col2", "fourth", "last")

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
          AttributeName.withDefaultNS("fourth") -> AttributeNumber(98.765),
          AttributeName.withDefaultNS("last") -> AttributeEntityReference("targetType", "targetName")
        )
      ),
      Entity(
        "789",
        entityType,
        Map(
          AttributeName.withDefaultNS("col1") -> AttributeString("baz\tqux"),
          AttributeName.withDefaultNS("col2") -> AttributeBoolean(true),
          AttributeName.withDefaultNS("fourth") -> AttributeNumber(-123.45),
          AttributeName.withDefaultNS("last") -> AttributeValueList(
            Seq(AttributeString("gs://some-bucket/somefile1.ext"),
                AttributeString("gs://some-bucket/somefile2.ext"),
                AttributeString("gs://some-bucket/somefile3.ext")
            )
          )
        )
      )
    )
  }

}

class TsvFormatterBenchmark {

  @Benchmark
  def makeEntityRows(blackHole: Blackhole, entityData: EntityData): List[List[String]] = {
    val result =
      TSVFormatter.makeEntityRows(entityData.entityType, entityData.entities, entityData.headers)(entityData.model)
    blackHole.consume(result)
    result
  }

}
