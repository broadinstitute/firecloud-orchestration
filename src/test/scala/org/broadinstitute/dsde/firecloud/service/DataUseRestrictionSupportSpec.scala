package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import org.broadinstitute.dsde.firecloud.integrationtest.SearchResultValidation
import org.broadinstitute.dsde.rawls.model.{ManagedGroupRef, Workspace, _}
import org.joda.time.DateTime
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._

object DataUseRestrictionTestFixture {

  case class DataUseRestriction(
    GRU: Boolean = false,
    HMB: Boolean = false,
    DS: Seq[String] = Seq.empty[String],
    NCU: Boolean = false,
    NPU: Boolean = false,
    NDMS: Boolean = false,
    NAGR: Boolean = false,
    NCTRL: Boolean = false,
    `RS-PD`: Boolean = false,
    `RS-G`: String = "",
    `RS-POP`: Seq[String] = Seq.empty[String])

  implicit val impDataUseRestriction: RootJsonFormat[DataUseRestriction] = jsonFormat11(DataUseRestriction)

  val testCases: Map[String, DataUseRestriction] =
    Map(
      "GRU" -> DataUseRestriction(GRU = true),
      "HMB" -> DataUseRestriction(HMB = true),
      "DS" -> DataUseRestriction(DS = Seq("DS-1", "DS-2")),
      "NCU" -> DataUseRestriction(NCU = true),
      "NPU" -> DataUseRestriction(NPU = true),
      "NDMS" -> DataUseRestriction(NDMS = true),
      "NAGR" -> DataUseRestriction(NAGR = true),
      "NCTRL" -> DataUseRestriction(NCTRL = true),
      "RS-PD" -> DataUseRestriction(`RS-PD` = true)
// TODO: How to test for the three gender cases
//      "rs-fm" -> DataUseRestriction(`RS-G` = "Female"),
//      "rs-m" -> DataUseRestriction(`RS-G` = "Male"),
//      "rs-n/a" -> DataUseRestriction(`RS-G` = "NA"),
//      "RS-POP" -> DataUseRestriction(`RS-POP` = Seq("POP-1", "POP-2"))
    )

  val datasets: Seq[Workspace] = testCases.map { tc =>
    val testUUID: UUID = UUID.randomUUID()
    val attributes = Map(AttributeName.withLibraryNS(tc._1) -> AttributeValueRawJson(tc._2.toJson.compactPrint))
    Workspace(
      workspaceId=testUUID.toString,
      namespace="testWorkspaceNamespace",
      name=tc._1,
      authorizationDomain=Set.empty[ManagedGroupRef],
      isLocked=false,
      createdBy="createdBy",
      createdDate=DateTime.now(),
      lastModified=DateTime.now(),
      attributes=attributes,
      bucketName="bucketName",
      accessLevels=Map.empty,
      authDomainACLs=Map())
  }.toSeq

}

class DataUseRestrictionSupportSpec extends FreeSpec with SearchResultValidation with Matchers with BeforeAndAfterAll with DataUseRestrictionSupport {


  "DataUseRestrictionSupport" - {

    "when there are library data use restriction fields" - {

      "workspace should have a fully populated data use restriction attribute" in {
        DataUseRestrictionTestFixture.datasets.map { ds =>
          val attrs = generateDataUseRestriction(ds)
          attrs.map(println)
          // TODO ... flesh out asserts
        }

      }

      "dur should have 'RS-FM' populated if 'RS-G' is 'Female'" in {
        // TODO
      }

      "dur should have 'RS-M' populated if 'RS-G' is 'Male'" in {
        // TODO
      }

      "dur should have 'RS-G' false if 'RS-G' is neither 'Male' or 'Female'" in {
        // TODO
      }

    }

    "when there are no library data use restriction fields" - {

      "workspace should not have a populated data use restriction attribute" in {
        // TODO
      }

    }

  }

}
