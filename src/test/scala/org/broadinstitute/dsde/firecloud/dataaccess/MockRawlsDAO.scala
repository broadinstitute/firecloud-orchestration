package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.firecloud.model.{RawlsWorkspace, RawlsWorkspaceResponse, UserInfo}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


/**
  * Created by davidan on 9/28/16.
  */
class MockRawlsDAO  extends RawlsDAO {

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = Future(true)

  override def getWorkspace(ns: String, name: String)(implicit userInfo: UserInfo): Future[RawlsWorkspaceResponse] = {
    Future(new RawlsWorkspaceResponse)
  }

  override def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userInfo: UserInfo): Future[RawlsWorkspace] = {
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


}
