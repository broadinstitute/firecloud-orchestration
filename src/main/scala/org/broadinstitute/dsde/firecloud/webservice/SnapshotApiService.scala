package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import org.broadinstitute.dsde.firecloud.utils.UserInfoDirectives
import spray.http.HttpMethods

object SnapshotApiService {
  val rawlsBasePath = FireCloudConfig.Rawls.baseUrl

  def createDataRepoSnapshotURL(namespace: String, name: String) = rawlsBasePath + s"/workspaces/${namespace}/${name}/snapshots"
  def getDataRepoSnapshotURL(namespace: String, name: String, snapshotId: String) = rawlsBasePath + s"/workspaces/${namespace}/${name}/snapshots/${snapshotId}"
}

trait SnapshotApiService extends FireCloudDirectives with UserInfoDirectives {

  val snapshotRoutes =
    pathPrefix("api") {
      pathPrefix("workspaces" / Segment / Segment ) { (namespace, name) =>
        pathEnd {
          post {
            passthrough(SnapshotApiService.createDataRepoSnapshotURL(namespace, name), HttpMethods.POST)
          }
        }
      } ~
      pathPrefix("workspaces" / Segment / Segment / "snapshots" / Segment) { (namespace, name, snapshotId) =>
        pathEnd {
          get {
            passthrough(SnapshotApiService.getDataRepoSnapshotURL(namespace, name, snapshotId), HttpMethods.GET)
          }
        }
      }
    }
}
