package org.broadinstitute.dsde.firecloud.service

import java.net.URL
import java.util.UUID

import org.broadinstitute.dsde.firecloud.dataaccess.ElasticSearchDAOSupport
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, _}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.LibraryService._
import org.everit.json.schema.ValidationException
import org.parboiled.common.FileUtils
import org.scalatest.freespec.AnyFreeSpecLike
import spray.json.{JsObject, _}
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Ontology.{TermParent, TermResource}
import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName
import org.joda.time.DateTime

import scala.jdk.CollectionConverters._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

object LibraryServiceSpec {
  val testLibraryMetadata =
    """
      |{
      |  "description" : "some description",
      |  "userAttributeOne" : "one",
      |  "userAttributeTwo" : "two",
      |  "library:datasetName" : "name",
      |  "library:datasetVersion" : "v1.0",
      |  "library:datasetDescription" : "desc",
      |  "library:datasetCustodian" : "cust",
      |  "library:datasetDepositor" : "depo",
      |  "library:contactEmail" : "name@example.com",
      |  "library:datasetOwner" : "owner",
      |  "library:institute" : ["inst","it","ute"],
      |  "library:indication" : "indic",
      |  "library:numSubjects" : 123,
      |  "library:projectName" : "proj",
      |  "library:datatype" : ["data","type"],
      |  "library:dataCategory" : ["data","category"],
      |  "library:dataUseRestriction" : "dur",
      |  "library:studyDesign" : "study",
      |  "library:cellType" : "cell",
      |  "library:requiresExternalApproval" : false,
      |  "library:technology" : ["is an optional","array attribute"],
      |  "library:useLimitationOption" : "orsp",
      |  "library:orsp" : "some orsp",
      |  "_discoverableByGroups" : ["Group1","Group2"]
      |}
    """.stripMargin
  val testLibraryMetadataJsObject = testLibraryMetadata.parseJson.asJsObject

}
class LibraryServiceSpec extends BaseServiceSpec with AnyFreeSpecLike with LibraryServiceSupport with AttributeSupport with ElasticSearchDAOSupport {
  def toName(s:String) = AttributeName.fromDelimitedName(s)

  implicit val userToken: WithAccessToken = AccessToken("LibraryServiceSpec")

  val libraryAttributePredicate = (k: AttributeName) => k.namespace == AttributeName.libraryNamespace && k.name != LibraryService.publishedFlag.name

  val existingLibraryAttrs = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "library:keythree"->"valthree", "library:keyfour"->"valfour").toJson.convertTo[AttributeMap]
  val existingMixedAttrs = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "keythree"->"valthree", "keyfour"->"valfour").toJson.convertTo[AttributeMap]
  val existingPublishedAttrs = Map("library:published"->"true", "library:keytwo"->"valtwo", "keythree"->"valthree", "keyfour"->"valfour").toJson.convertTo[AttributeMap]

  val testUUID = UUID.randomUUID()

  val testGroup1Ref = ManagedGroupRef(RawlsGroupName("test-group1"))
  val testGroup2Ref = ManagedGroupRef(RawlsGroupName("test-group2"))

  val testWorkspace = new WorkspaceDetails(workspaceId = testUUID.toString,
    namespace = "testWorkspaceNamespace",
    name = "testWorkspaceName",
    authorizationDomain = Some(Set(testGroup1Ref, testGroup2Ref)),
    isLocked = false,
    createdBy = "createdBy",
    createdDate = DateTime.now(),
    lastModified = DateTime.now(),
    attributes = Some(Map.empty),
    bucketName = "bucketName",
    workflowCollectionName = Some("wf-collection"),
    workspaceVersion=WorkspaceVersions.V2,
    googleProject = GoogleProjectId("googleProject"),
    googleProjectNumber = Some(GoogleProjectNumber("googleProjectNumber")),
    billingAccount = Some(RawlsBillingAccountName("billingAccount")),
    completedCloneWorkspaceFileTransfer = Some(DateTime.now()),
    workspaceType = None
  )


  val DULAdditionalJsObject =
    """
      |{
      |  "library:GRU"  : true,
      |  "library:HMB"  : false,
      |  "library:NCU"  : false,
      |  "library:NPU"  : false,
      |  "library:NMDS" : true,
      |  "library:NAGR" : "Unspecified",
      |  "library:NCTRL": true,
      |  "library:RS-G" : "Female",
      |  "library:RS-PD": false,
      |  "library:IRB"  : false
      |}
    """.stripMargin.parseJson.asJsObject
  val DULfields = (LibraryServiceSpec.testLibraryMetadataJsObject.fields-"library:orsp") ++ DULAdditionalJsObject.fields
  val testLibraryDULMetadata = LibraryServiceSpec.testLibraryMetadataJsObject.copy(DULfields).compactPrint

  val dur = Duration(2, MINUTES)

  "LibraryService" - {
    "when new attrs are empty" - {
      "should calculate all attribute removals" in {
        val newAttrs = """{}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keyone")),
          RemoveAttribute(toName("library:keytwo")),
          RemoveAttribute(toName("library:keythree")),
          RemoveAttribute(toName("library:keyfour"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs, libraryAttributePredicate)
        }
      }
    }
    "when new attrs are a subset" - {
      "should calculate removals and updates" in {
        val newAttrs = """{"library:keyone":"valoneNew", "library:keytwo":"valtwoNew"}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keythree")),
          RemoveAttribute(toName("library:keyfour")),
          AddUpdateAttribute(toName("library:keyone"),AttributeString("valoneNew")),
          AddUpdateAttribute(toName("library:keytwo"),AttributeString("valtwoNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs, libraryAttributePredicate)
        }
      }
    }
    "when new attrs contain an array" - {
      "should calculate list updates" in {
        val newAttrs =
          """{"library:keyone":"valoneNew",
            | "library:keytwo":
            | {
            |   "itemsType" : "AttributeValue",
            |   "items" : ["valtwoA","valtwoB","valtwoC"]
            | }
            |}""".stripMargin.parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keythree")),
          RemoveAttribute(toName("library:keyfour")),
          RemoveAttribute(toName("library:keytwo")),
          AddUpdateAttribute(toName("library:keyone"), AttributeString("valoneNew")),
          AddListMember(toName("library:keytwo"), AttributeString("valtwoA")),
          AddListMember(toName("library:keytwo"), AttributeString("valtwoB")),
          AddListMember(toName("library:keytwo"), AttributeString("valtwoC"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs, libraryAttributePredicate)
        }
      }
    }
    "when old attrs include non-library" - {
      "should not touch old non-library attrs" in {
        val newAttrs = """{"library:keyone":"valoneNew"}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keytwo")),
          AddUpdateAttribute(toName("library:keyone"),AttributeString("valoneNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingMixedAttrs, newAttrs, libraryAttributePredicate)
        }
      }
    }
    "when new attrs include non-library" - {
      "should not touch new non-library attrs" in {
        val newAttrs = """{"library:keyone":"valoneNew", "library:keytwo":"valtwoNew", "333":"three", "444":"four"}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keythree")),
          RemoveAttribute(toName("library:keyfour")),
          AddUpdateAttribute(toName("library:keyone"),AttributeString("valoneNew")),
          AddUpdateAttribute(toName("library:keytwo"),AttributeString("valtwoNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs, libraryAttributePredicate)
        }
      }
    }
    "when old attrs include published flag" - {
      "should not touch old published flag" in {
        val newAttrs = """{"library:keyone":"valoneNew"}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keytwo")),
          AddUpdateAttribute(toName("library:keyone"),AttributeString("valoneNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingPublishedAttrs, newAttrs, libraryAttributePredicate)
        }
      }
    }
    "when new attrs include published flag" - {
      "should not touch old published flag" in {
        val newAttrs = """{"library:published":"true","library:keyone":"valoneNew", "library:keytwo":"valtwoNew"}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keythree")),
          RemoveAttribute(toName("library:keyfour")),
          AddUpdateAttribute(toName("library:keyone"),AttributeString("valoneNew")),
          AddUpdateAttribute(toName("library:keytwo"),AttributeString("valtwoNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs, libraryAttributePredicate)
        }
      }
    }
    "when publishing a workspace" - {
      "should add a library:published attribute" in {
        val expected = Seq(AddUpdateAttribute(toName("library:published"),AttributeBoolean(true)))
        assertResult(expected) {
          updatePublishAttribute(true)
        }
      }
    }
    "when unpublishing a workspace" - {
      "should remove the library:published attribute" in {
        val expected = Seq(RemoveAttribute(toName("library:published")))
        assertResult(expected) {
          updatePublishAttribute(false)
        }
      }
    }
    "with only library attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar")
        )))
        val expected = Document(testUUID.toString, Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
    }
    "with only default attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","discoverableByGroups")->AttributeValueList(Seq(AttributeString("Group1"))),
          AttributeName.withDefaultNS("baz")->AttributeString("defaultBaz"),
          AttributeName.withDefaultNS("qux")->AttributeString("defaultQux")
        )))
        val expected = Document(testUUID.toString, Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName.withDefaultNS("_discoverableByGroups") -> AttributeValueList(Seq(AttributeString("Group1"))),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
    }
    "with discoverableByGroup attribute in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          AttributeName.withDefaultNS("baz")->AttributeString("defaultBaz"),
          AttributeName.withDefaultNS("qux")->AttributeString("defaultQux")
        )))
        val expected = Document(testUUID.toString, Map(
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
      "should be the different for attribute operations" in {
        val empty = WorkspaceResponse(Some(WorkspaceAccessLevels.NoAccess), Some(false), Some(true), Some(false), testWorkspace.copy(attributes = Some(Map(discoverableWSAttribute->AttributeValueList(Seq.empty)))), Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None)
        assert(isDiscoverableDifferent(empty, Map(discoverableWSAttribute->AttributeValueList(Seq(AttributeString("group1"))))))
        val one = WorkspaceResponse(Some(WorkspaceAccessLevels.NoAccess), Some(false), Some(true), Some(false), testWorkspace.copy(attributes = Some(Map(discoverableWSAttribute->AttributeValueList(Seq(AttributeString("group1")))))), Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None)
        assert(isDiscoverableDifferent(one, Map(discoverableWSAttribute->AttributeValueList(Seq(AttributeString("group1"),AttributeString("group2"))))))
        assert(isDiscoverableDifferent(one, Map(discoverableWSAttribute->AttributeValueList(Seq.empty))))
        val two = WorkspaceResponse(Some(WorkspaceAccessLevels.NoAccess), Some(false), Some(true), Some(false), testWorkspace.copy(attributes = Some(Map(discoverableWSAttribute->AttributeValueList(Seq(AttributeString("group1"),AttributeString("group2")))))), Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), Some(WorkspaceBucketOptions(false)), Some(Set.empty), None)
        assert(isDiscoverableDifferent(two, Map(discoverableWSAttribute->AttributeValueList(Seq(AttributeString("group2"))))))
        assert(!isDiscoverableDifferent(two, Map(discoverableWSAttribute->AttributeValueList(Seq(AttributeString("group2"),AttributeString("group1"))))))
      }
    }
    "with no attributes in workspace" - {
      "should generate indexable document" in {
        // the Map.empty below is currently the same as what's in testWorkspace;
        // include explicitly here in case testWorkspace changes later
        val w = testWorkspace.copy(attributes = Some(Map.empty))
        val expected = Document(testUUID.toString, Map(
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
    }
    "with just a (longish) description in workspace" - {
      "should generate indexable document" in {
        // https://hipsum.co/
        val w = testWorkspace.copy(attributes = Some(Map(
          AttributeName.withDefaultNS("description")->AttributeString("Fingerstache copper mug edison bulb, actually austin mustache chartreuse bicycle rights." +
            " Plaid iceland artisan blog street art hammock, subway tile vice. Hammock put a bird on it pinterest tacos" +
            " kitsch gastropub. Chicharrones food truck edison bulb meh. Cardigan aesthetic vegan kitsch. Hell of" +
            " messenger bag chillwave hashtag, distillery thundercats aesthetic roof party lo-fi sustainable" +
            " jean shorts single-origin coffee. Distillery ugh green juice, hammock marfa gastropub mlkshk" +
            " chambray vegan aesthetic beard listicle skateboard ramps literally.")
        )))
        val expected = Document(testUUID.toString, Map(
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
    }
    "with mixed library and default attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar"),
          AttributeName.withDefaultNS("baz")->AttributeString("defaultBaz"),
          AttributeName.withDefaultNS("qux")->AttributeString("defaultQux")
        )))
        val expected = Document(testUUID.toString, Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
    }
    "with illegally-namespaced attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar"),
          AttributeName.withDefaultNS("baz")->AttributeString("defaultBaz"),
          AttributeName.withDefaultNS("qux")->AttributeString("defaultQux"),
          AttributeName("nope","foo")->AttributeString("foo"),
          AttributeName("default","bar")->AttributeString("bar")
        )))
        val expected = Document(testUUID.toString, Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
    }
    "with diseaseOntologyID attribute" - {
      "should generate indexable document with parent info when DOID valid" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("http://purl.obolibrary.org/obo/DOID_9220")
        )))
        val parentData = ontologyDao.data("http://purl.obolibrary.org/obo/DOID_9220").head.parents.get.map(_.toESTermParent)
        val expected = Document(testUUID.toString, Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("http://purl.obolibrary.org/obo/DOID_9220"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("parents") -> AttributeValueRawJson(parentData.toJson.compactPrint),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
      "should generate indexable document with no parent info when DOID has no parents" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("http://purl.obolibrary.org/obo/DOID_4")
        )))
        val expected = Document(testUUID.toString, Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("http://purl.obolibrary.org/obo/DOID_4"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
      "should generate indexable document with no parent info when DOID not valid" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("http://purl.obolibrary.org/obo/DOID_99999")
        )))
        val expected = Document(testUUID.toString, Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("http://purl.obolibrary.org/obo/DOID_99999"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
    }
    "with an ORSP id in attributes" - {
      // most of this is unit-tested in DataUseRestrictionSupportSpec; tests here are intentionally high level

      // default json object fields to represent the indexed data use restriction
      val defaultDataUseFields = Map(
        "NPU" -> JsBoolean(false),
        "RS-PD" -> JsBoolean(false),
        "NCU" -> JsBoolean(false),
        "RS-G" -> JsBoolean(false),
        "IRB" -> JsBoolean(false),
        "NAGR" -> JsBoolean(false),
        "RS-FM" -> JsBoolean(false),
        "RS-M" -> JsBoolean(false),
        "NMDS" -> JsBoolean(false),
        "NCTRL" -> JsBoolean(false),
        "GRU" -> JsBoolean(false),
        "HMB" -> JsBoolean(false),
        "DS" -> JsArray()
      )

      "should populate data use restrictions from Consent" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          orspIdAttribute -> AttributeString("MOCK-111")
        )))
        val expected = Document(testUUID.toString, Map(
          orspIdAttribute -> AttributeString("MOCK-111"),
          consentCodesAttributeName -> AttributeValueList(Seq(AttributeString("NCTRL"), AttributeString("NCU"))),
          structuredUseRestrictionAttributeName -> AttributeValueRawJson(JsObject(
            defaultDataUseFields ++ Map(
              "NCU" -> JsBoolean(true),
              "NCTRL" -> JsBoolean(true)
            )).prettyPrint),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
      "should clear and overwrite pre-existing data use attributes" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          orspIdAttribute -> AttributeString("MOCK-111"),
          AttributeName.withLibraryNS("NCU") -> AttributeBoolean(false), // should be overwritten by orsp DU
          AttributeName.withLibraryNS("GRU") -> AttributeBoolean(true) // overrides the default, should be erased by orsp DU
        )))
        val expected = Document(testUUID.toString, Map(
          orspIdAttribute -> AttributeString("MOCK-111"),
          consentCodesAttributeName -> AttributeValueList(Seq(AttributeString("NCTRL"), AttributeString("NCU"))),
          structuredUseRestrictionAttributeName -> AttributeValueRawJson(JsObject(
            defaultDataUseFields ++ Map(
              "NCU" -> JsBoolean(true),
              "NCTRL" -> JsBoolean(true)
            )).prettyPrint),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
      "should preserve pre-existing non-data use attributes" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          orspIdAttribute -> AttributeString("MOCK-111"),
          AttributeName.withLibraryNS("datasetName") -> AttributeString("my cohort"),
          AttributeName.withLibraryNS("projectName") -> AttributeString("my project")

        )))
        val expected = Document(testUUID.toString, Map(
          orspIdAttribute -> AttributeString("MOCK-111"),
          consentCodesAttributeName -> AttributeValueList(Seq(AttributeString("NCTRL"), AttributeString("NCU"))),
          structuredUseRestrictionAttributeName -> AttributeValueRawJson(JsObject(
            defaultDataUseFields ++ Map(
              "NCU" -> JsBoolean(true),
              "NCTRL" -> JsBoolean(true)
            )).prettyPrint),
          AttributeName.withLibraryNS("datasetName") -> AttributeString("my cohort"),
          AttributeName.withLibraryNS("projectName") -> AttributeString("my project"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
      "should generate indexable document without any data use restrictions if ORSP id not found" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          orspIdAttribute -> AttributeString("MOCK-NOTFOUND")
        )))
        val expected = Document(testUUID.toString, Map(
          orspIdAttribute -> AttributeString("MOCK-NOTFOUND"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
      "should generate indexable document without any data use restrictions if ORSP request throws exception" in {
        val w = testWorkspace.copy(attributes = Some(Map(
          orspIdAttribute -> AttributeString("MOCK-EXCEPTION")
        )))
        val expected = Document(testUUID.toString, Map(
          orspIdAttribute -> AttributeString("MOCK-EXCEPTION"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("authorizationDomain") -> AttributeValueList(Seq(AttributeString(testGroup1Ref.membersGroupName.value), AttributeString(testGroup2Ref.membersGroupName.value)))
        ))
        assertResult(expected) {
          Await.result(indexableDocuments(Seq(w), ontologyDao, consentDao), dur).head
        }
      }
    }
    "in its runtime schema definition" - {
      "has valid JSON" in {
        val fileContents = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val jsonVal:Try[JsValue] = Try(fileContents.parseJson)
        assert(jsonVal.isSuccess, "Schema should be valid json")
      }
      "has valid JSON Schema" in {
        /*
          src/test/resources/json-schema-draft-04.json is a stashed copy of http://json-schema.org/draft-04/schema.
          We've seen the json-schema.org domain go down twice, so we use a stashed copy here in this test
          instead of reading directly from json-schema.org.
         */
        val schemaContents = FileUtils.readAllTextFromResource("json-schema-draft-04.json")
        val fileContents = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        validateJsonSchema(fileContents, schemaContents)
      }
    }
    "when validating JSON Schema" - {
      "fails on an empty JsObject" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val sampleData = "{}"
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(31){ex.getViolationCount}
      }
      "fails with one missing key" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = LibraryServiceSpec.testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:datasetName").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1){ex.getViolationCount}
        assert(ex.getCausingExceptions.asScala.last.getMessage.contains("library:datasetName"))
      }
      "fails with two missing keys" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = LibraryServiceSpec.testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:datasetName"-"library:datasetOwner").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(2){ex.getViolationCount}
      }
      "fails on a string that should be a number" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = LibraryServiceSpec.testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:numSubjects", JsString("isString"))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1){ex.getViolationCount}
        assert(ex.getCausingExceptions.asScala.last.getMessage.contains("library:numSubjects"))
      }
      "fails on a number out of bounds" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = LibraryServiceSpec.testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:numSubjects", JsNumber(-1))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1){ex.getViolationCount}
        assert(ex.getCausingExceptions.asScala.last.getMessage.contains("library:numSubjects"))
      }
      "fails on a value outside its enum" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = LibraryServiceSpec.testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:coverage", JsString("foobar"))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1){ex.getViolationCount}
        // getSchemaValidationMessages is used at runtime to generate error messages to the user; it recurses through
        // the exception and its causes.
        val errMsgs = getSchemaValidationMessages(ex)
        assert(
          errMsgs.exists(_.contains("library:coverage: foobar is not a valid enum value")),
          "did not find expected string 'library:coverage: foobar is not a valid enum value' in error messages"
        )
      }
      "fails on a string that should be an array" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = LibraryServiceSpec.testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:institute", JsString("isString"))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1){ex.getViolationCount}
        assert(ex.getCausingExceptions.asScala.last.getMessage.contains("library:institute"))
      }
      "fails with missing ORSP key" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = LibraryServiceSpec.testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:orsp").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        // require violations inside the oneOf schemas are extra-nested, and can include errors from all
        // subschemas in the oneOf list. Not worth testing in detail.
      }
      "validates on a complete metadata packet with ORSP key" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        validateJsonSchema(LibraryServiceSpec.testLibraryMetadata, testSchema)
      }
      "fails with one missing key from the DUL set" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryDULMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:NPU").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        // require violations inside the oneOf schemas are extra-nested, and can include errors from all
        // subschemas in the oneOf list. Not worth testing in detail.
      }
      "fails on a metadata packet with all DUL keys but the wrong option chosen" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val ex = intercept[ValidationException] {
          validateJsonSchema(testLibraryDULMetadata, testSchema)
        }
      }
      "validates on a complete metadata packet with all DUL keys and the correct option chosen" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryDULMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:useLimitationOption", JsString("questionnaire"))).compactPrint
        validateJsonSchema(sampleData, testSchema)
      }

      "has error messages for top-level missing keys" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = LibraryServiceSpec.testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:datasetName"-"library:datasetOwner").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        val errorMessages = getSchemaValidationMessages(ex)
        assert( errorMessages.contains("#: required key [library:datasetName] not found"),
          "does not have library:datasetName in error messages" )
        assert( errorMessages.contains("#: required key [library:datasetOwner] not found"),
          "does not have library:datasetOwner in error messages" )
      }
      "has error message for missing key from the DUR set" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryDULMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:NPU").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        val errorMessages = getSchemaValidationMessages(ex)
        assert( errorMessages.contains("#: required key [library:NPU] not found"),
          "does not have library:NPU in error messages" )
      }
      "has error message when primary DUL keys (GRU, HMB, DS) are specified but are all false/empty" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryDULMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields ++ Map(
          "library:HMB" -> JsBoolean(false),
          "library:GRU" -> JsBoolean(false),
          "library:DS" -> JsArray.empty
        )).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        val errorMessages = getSchemaValidationMessages(ex)
        assert( errorMessages.contains("#/library:GRU: false is not a valid enum value") )
        assert( errorMessages.contains("#/library:HMB: false is not a valid enum value") )
        assert( errorMessages.contains("#/library:DS: expected minimum item count: 1, found: 0") )
      }
      "has error message when library:DS is not an array" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryDULMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated(
          "library:DS", JsString("astring")
        )).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        val errorMessages = getSchemaValidationMessages(ex)
        assert( errorMessages.contains("#/library:DS: expected type: JSONArray, found: String") )
      }
      "validates when multiple primary DUL keys are true" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryDULMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields ++ Map(
          "library:useLimitationOption" -> JsString("questionnaire"),
          "library:HMB" -> JsBoolean(true),
          "library:GRU" -> JsBoolean(true),
          "library:DS" -> JsArray(JsString("foo"))
        )).compactPrint
        validateJsonSchema(sampleData, testSchema)
      }
    }
    "when creating schema mappings" - {
      "works for string type" in {
        val label = "library:attr"
        val `type` = "string"
        val expected = label -> ESType(`type`, false, true, false)
        assertResult(expected) {
          createType(label, AttributeDetail(`type`))
        }
      }
      "works for aggregatable string type" in {
        val label = "library:attr"
        val `type` = "string"
        val aggregateObject = JsObject("renderHint"->JsString("text"))
        val expected = label -> ESType(`type`, false, true, true)
        assertResult(expected) {
          createType(label, AttributeDetail(`type`, None, Some(aggregateObject)))
        }
        val result = createType(label, AttributeDetail(`type`, None, Some(aggregateObject)))
      }
      "works for array type" in {
        val label = "library:attr"
        val `type` = "array"
        val subtype = "string"
        val detail = AttributeDetail(`type`, Some(AttributeDetail(subtype)))
        val expected = label -> ESType(subtype, false, true, false)
        assertResult(expected) {
          createType(label, detail)
        }
      }
      "works for aggregatable array type" in {
        val label = "library:attr"
        val `type` = "array"
        val subtype = "string"
        val aggregateObject = JsObject("renderHint"->JsString("text"))
        val detail = AttributeDetail(`type`, Some(AttributeDetail(subtype)), Some(aggregateObject))
        val expected = label -> ESType(subtype, false, true, true)
        assertResult(expected) {
          createType(label, detail)
        }
      }
      "works for populate suggest array type" in {
        val label = "library:datatype"
        val `type` = "string"
        val detail = AttributeDetail(`type`, None, None, None, Option("populate"))
        val expected = label -> ESType(`type`, true, true, false)
        assertResult(expected) {
          createType(label, detail)
        }
      }
      "mapping has valid json" in {
        val attrJson = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val testJson = makeMapping(attrJson)
        val jsonVal: Try[JsValue] = Try(testJson.parseJson)
        assert(jsonVal.isSuccess, "Mapping should be valid json")
      }
      "non-indexable type is not in mapping" in {
        val attrJson = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val testJson = makeMapping(attrJson)
        val label = "library:lmsvn"
        assert(!testJson.contains(label))
      }
      "discoverableByGroups is in mapping" in {
        val attrJson = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val testJson = makeMapping(attrJson)
        assert(testJson.contains(ElasticSearch.fieldDiscoverableByGroups))
      }
    }
    "when finding unique string attributes in a Seq of Workspaces" - {

      def makeWorkspacesWithAttributes(attrMaps: Seq[AttributeMap]): Seq[WorkspaceDetails] = {
        val ws = WorkspaceDetails(
          "namespace",
          "name",
          "workspace_id",
          "buckety_bucket",
          Some("wf-collection"),
          DateTime.now(),
          DateTime.now(),
          "my_workspace_creator",
          Some(Map()), //attributes
          false, //locked
          Some(Set.empty), //authdomain
          WorkspaceVersions.V2,
          GoogleProjectId("googleProject"),
          Some(GoogleProjectNumber("googleProjectNumber")),
          Some(RawlsBillingAccountName("billingAccount")),
          None,
          Option(DateTime.now()),
          None
        )

        attrMaps map { attrMap =>
          ws.copy(attributes = Some(attrMap), workspaceId = UUID.randomUUID().toString)
        }
      }

      "should return an empty list when no workspaces" in {
        val workspaces = Seq.empty[WorkspaceDetails]
        assertResult(Set.empty[String]) {
          uniqueWorkspaceStringAttributes(workspaces, AttributeName.withDefaultNS("description"))
        }
      }
      "should return an empty list when no attributes" in {
        val workspaces = makeWorkspacesWithAttributes(Seq(Map(), Map(), Map()))
        assertResult(Set.empty[String]) {
          uniqueWorkspaceStringAttributes(workspaces, AttributeName.withDefaultNS("description"))
        }
      }
      "should return a list when some attributes" in {
        val workspaces = makeWorkspacesWithAttributes(Seq(
          Map(AttributeName.withLibraryNS("something") -> AttributeString("one")),
          Map(AttributeName.withLibraryNS("something") -> AttributeString("two")),
          Map(AttributeName.withLibraryNS("something") -> AttributeString("three"))
        ))
        assertResult(Set("one","two","three")) {
          uniqueWorkspaceStringAttributes(workspaces, AttributeName.withLibraryNS("something"))
        }
      }
      "should not return duplicate attributes" in {
        val workspaces = makeWorkspacesWithAttributes(Seq(
          Map(AttributeName.withDefaultNS("something") -> AttributeString("one")),
          Map(AttributeName.withDefaultNS("something") -> AttributeString("two")),
          Map(AttributeName.withDefaultNS("something") -> AttributeString("two"))
        ))
        assertResult(Set("one","two")) {
          uniqueWorkspaceStringAttributes(workspaces, AttributeName.withDefaultNS("something"))
        }
      }
      "should ignore non-string attributes" in {
        val workspaces = makeWorkspacesWithAttributes(Seq(
          Map(AttributeName.withDefaultNS("something") -> AttributeString("one")),
          Map(AttributeName.withDefaultNS("something") -> AttributeNumber(2)),
          Map(AttributeName.withDefaultNS("something") -> AttributeValueList(Seq(AttributeString("two"))))
        ))
        assertResult(Set("one")) {
          uniqueWorkspaceStringAttributes(workspaces, AttributeName.withDefaultNS("something"))
        }
      }
      "should ignore attributes beyond our target name" in {
        val workspaces = makeWorkspacesWithAttributes(Seq(
          Map(
            AttributeName.withDefaultNS("something") -> AttributeString("one"),
            AttributeName.withLibraryNS("something") -> AttributeString("three") // different namespace
          ),
          Map(
            AttributeName.withDefaultNS("something") -> AttributeString("two"),
            AttributeName.withDefaultNS("hi") -> AttributeString("two") // different name
          )
        ))
        assertResult(Set("one","two")) {
          uniqueWorkspaceStringAttributes(workspaces, AttributeName.withDefaultNS("something"))
        }
      }
      "should ignore workspaces that don't have our target name" in {
        val workspaces = makeWorkspacesWithAttributes(Seq(
          Map(AttributeName.withDefaultNS("something") -> AttributeString("one")),
          Map(AttributeName.withDefaultNS("somethingElse") -> AttributeString("two")),
          Map(),
          Map(AttributeName.withDefaultNS("something") -> AttributeString("four"))
        ))
        assertResult(Set("one","four")) {
          uniqueWorkspaceStringAttributes(workspaces, AttributeName.withDefaultNS("something"))
        }
      }
    }
    "getting effective discoverable by groups" - {
      "should include all_broad_users" in {
        implicit val userInfo = UserInfo("thisismytoken", "thisismysubjectid")
        samDao.createGroup(WorkbenchGroupName("all_broad_users"))
        assertResult(Seq("all_broad_users")) {
          Await.result(getEffectiveDiscoverGroups(samDao), dur)
        }
      }
    }
  }
}
