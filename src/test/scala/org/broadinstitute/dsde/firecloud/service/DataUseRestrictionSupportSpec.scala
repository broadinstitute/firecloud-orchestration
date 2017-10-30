package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.AttributeNameFormat
import org.broadinstitute.dsde.firecloud.integrationtest.SearchResultValidation
import org.broadinstitute.dsde.rawls.model.{ManagedGroupRef, Workspace, _}
import org.joda.time.DateTime
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._

object DataUseRestrictionTestFixture {

  val datasets: Seq[Workspace] = List("GRU", "HMB", "NCU", "NPU", "NDMS", "NAGR", "NCTRL","RS-PD").map { code =>
    val attributes = Map(AttributeName.withLibraryNS(code) -> AttributeBoolean(true))
    mkWorkspace(attributes, code)
  } ++ List("DS", "RS-POP").map { code =>
    val attributes = Map(AttributeName.withLibraryNS(code) -> AttributeValueList(Seq(AttributeString("TERM-1"), AttributeString("TERM-2"))))
    mkWorkspace(attributes, code)
  } ++ List("Female", "Male", "N/A").map { gender =>
    val attributes = Map(AttributeName.withLibraryNS("RS-G") -> AttributeString(gender))
    mkWorkspace(attributes, "RS-G")
  }

  private def mkWorkspace(attributes: Map[AttributeName, Attribute], code: String): Workspace = {
    val testUUID: UUID = UUID.randomUUID()
    Workspace(
      workspaceId=testUUID.toString,
      namespace="testWorkspaceNamespace",
      name=code,
      authorizationDomain=Set.empty[ManagedGroupRef],
      isLocked=false,
      createdBy="createdBy",
      createdDate=DateTime.now(),
      lastModified=DateTime.now(),
      attributes=attributes,
      bucketName="bucketName",
      accessLevels=Map.empty,
      authDomainACLs=Map())
  }

}

class DataUseRestrictionSupportSpec extends FreeSpec with SearchResultValidation with Matchers with BeforeAndAfterAll with DataUseRestrictionSupport {

  implicit val impAttributeFormat: AttributeFormat with PlainArrayAttributeListSerializer =
    new AttributeFormat with PlainArrayAttributeListSerializer

  "DataUseRestrictionSupport" - {

    "when there are library data use restriction fields" - {

      "workspace should have a fully populated data use restriction attribute" in {
        DataUseRestrictionTestFixture.datasets.map { ds =>
          val attrs = generateDataUseRestriction(ds)
          attrs.map { attrs => println(attrs.toJson.prettyPrint) }
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
