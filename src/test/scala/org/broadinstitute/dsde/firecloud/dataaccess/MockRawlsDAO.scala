package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.firecloud.model.{AttributeName, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


/**
  * Created by davidan on 9/28/16.
  *
  * Not currently used; serves as example code only
  *
  */
class MockRawlsDAO  extends RawlsDAO {

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = Future(true)

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = Future(true)

  private val rawlsWorkspaceWithAttributes = RawlsWorkspace(
    "id",
    "attributes",
    "att",
    Option(false),
    "ansingh",
    "date",
    Some("date"),
    Map(AttributeName("default", "a") -> AttributeBoolean(true),
      AttributeName("default", "b") -> AttributeNumber(1.23),
      AttributeName("default", "c") -> AttributeString(""),
      AttributeName("default", "d") -> AttributeString("escape quo\"te"),
      AttributeName("default", "e") -> AttributeString("v1"),
      AttributeName("default", "f") -> AttributeValueList(Seq(
        AttributeString("v6"),
        AttributeNumber(999),
        AttributeBoolean(true)
      ))),
    "",
    Map("" -> Map("" -> "")),
    Some(Map("" -> ""))
  )

  private val rawlsWorkspaceResponseWithAttributes = RawlsWorkspaceResponse(Some(""), Some(rawlsWorkspaceWithAttributes), None, None)


  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[RawlsWorkspaceResponse] = {
    ns match {
      case "projectowner" => Future(RawlsWorkspaceResponse(Some("PROJECT_OWNER")))
      case "reader" => Future(RawlsWorkspaceResponse(Some("READER")))
      case "attributes" => Future(rawlsWorkspaceResponseWithAttributes)
      case _ => Future(RawlsWorkspaceResponse(Some("OWNER")))
    }

  }

  override def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[RawlsWorkspace] = {
    Future(new RawlsWorkspace(
      workspaceId = "workspaceId",
      namespace = "namespace",
      name = "name",
      isLocked = Some(true),
      createdBy = "createdBy",
      createdDate = "createdDate",
      lastModified = None,
      attributes = Map(),
      bucketName = "bucketName",
      accessLevels = Map(),
      realm = None))
  }

  override def getAllLibraryPublishedWorkspaces: Future[Seq[RawlsWorkspace]] = Future(Seq.empty[RawlsWorkspace])

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate])(implicit userToken: WithAccessToken): Future[Seq[WorkspaceACLUpdate]] = {
    Future(aclUpdates)
  }

}
