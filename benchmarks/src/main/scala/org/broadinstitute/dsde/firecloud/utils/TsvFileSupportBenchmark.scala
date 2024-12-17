package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.model.{EntityUpdateDefinition, FlexibleModelSchema, ModelSchema}
import org.broadinstitute.dsde.firecloud.service.TSVFileSupport
import org.broadinstitute.dsde.firecloud.utils.TsvFileSupportBenchmark.TsvData
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}
import org.openjdk.jmh.infra.Blackhole

object TsvFileSupportBenchmark {
  @State(Scope.Thread)
  class TsvData {
    val entityType: String = "sample"
    val memberTypeOpt: Option[String] = None
    // representation of the inbound TSV row
    val row: Seq[String] = Seq(
      "0005",
      "foo",
      "\"some\tquoted\tvalue\"",
      "42",
      "true",
      "-123.456",
      "gs://some-bucket/somefile.ext",
      """{"entityType":"targetType", "entityName":"targetName"}""",
      "[1,2,3,4,5]"
    )
    val colInfo: Seq[(String, Option[String])] = Seq(
      ("sample_id", None),
      ("string", None),
      ("quotedstring", None),
      ("int", None),
      ("boolean", None),
      ("double", None),
      ("file", None),
      ("reference", None),
      ("array", None)
    )
    val modelSchema: ModelSchema = FlexibleModelSchema
    val deleteEmptyValues: Boolean = false
  }
}

class TsvFileSupportBenchmark {

  @Benchmark
  def makeEntityRows(blackHole: Blackhole, tsvData: TsvData): EntityUpdateDefinition = {

    val result: EntityUpdateDefinition = TsvFileSupportHarness.setAttributesOnEntity(tsvData.entityType,
                                                                                     tsvData.memberTypeOpt,
                                                                                     tsvData.row,
                                                                                     tsvData.colInfo,
                                                                                     tsvData.modelSchema,
                                                                                     tsvData.deleteEmptyValues
    )
    blackHole.consume(result)
    result
  }

}

// helper object to get access to TSVFileSupport trait
object TsvFileSupportHarness extends TSVFileSupport {}
