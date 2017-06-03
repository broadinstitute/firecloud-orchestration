package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, Method}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import spray.http.HttpResponse

import scala.collection.mutable
import scala.concurrent.Future

/**
  * each individual unit test should use a new instance of this mock dao, because it records
  * the input for each call. It could be extended to also record the responses from each call.
  */
class StatefulMockAgoraDAO extends MockAgoraDAO {

  var postMethodCalls = mutable.MutableList.empty[(String,String,String,String,String)]
  var redactMethodCalls = mutable.MutableList.empty[(String,String,Int)]
  var getMethodPermissionsCalls = mutable.MutableList.empty[(String,String,Int)]
  var postMethodPermissionsCalls = mutable.MutableList.empty[(String,String,Int,List[AgoraPermission])]

  override def postMethod(ns: String, name: String, synopsis: Option[String], documentation: Option[String], payload: String)(implicit userInfo: UserInfo): Future[Method] = {
    postMethodCalls += ((ns,name,synopsis.get,documentation.get,payload))
    super.postMethod(ns, name, synopsis, documentation, payload)
  }

  override def redactMethod(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[HttpResponse] = {
    redactMethodCalls += ((ns,name,snapshotId))
    super.redactMethod(ns, name, snapshotId)
  }

  override def getMethodPermissions(ns: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    getMethodPermissionsCalls += ((ns,name,snapshotId))
    super.getMethodPermissions(ns, name, snapshotId)
  }

  override def postMethodPermissions(ns: String, name: String, snapshotId: Int, perms: List[AgoraPermission])(implicit userInfo: UserInfo): Future[List[AgoraPermission]] = {
    postMethodPermissionsCalls += ((ns,name,snapshotId,perms))
    super.postMethodPermissions(ns, name, snapshotId, perms)
  }

}
