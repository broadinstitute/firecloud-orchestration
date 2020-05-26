package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import org.broadinstitute.dsde.firecloud.utils.UserInfoDirectives
import spray.http.{HttpMethods, Uri}

object SnapshotApiService {
  val rawlsBasePath = FireCloudConfig.Rawls.baseUrl

  def createDataRepoSnapshotURL(namespace: String, name: String) = rawlsBasePath + s"/workspaces/${namespace}/${name}/snapshots"
  def listDataRepoSnapshotURL(namespace:String, name:String) = rawlsBasePath + s"/workspaces/${namespace}/${name}/snapshots"
  def dataRepoSnapshotURL(namespace: String, name: String, snapshotId: String) = rawlsBasePath + s"/workspaces/${namespace}/${name}/snapshots/${snapshotId}"
}

trait SnapshotApiService extends FireCloudDirectives with UserInfoDirectives {

  val snapshotRoutes =
    pathPrefix("api") {
      pathPrefix("workspaces" / Segment / Segment / "snapshots") { (namespace, name) =>
        pathEnd {
          post {
            passthrough(SnapshotApiService.createDataRepoSnapshotURL(namespace, name), HttpMethods.POST)
          } ~
          get {
            pathPrefix("workspaces" / Segment / Segment / "snapshots") { (namespace, name) =>
              extract(_.request.uri.query) { query =>
                passthrough(Uri(SnapshotApiService.listDataRepoSnapshotURL(namespace, name)).withQuery(query), HttpMethods.GET)
              }
            }
          }
        }
      } ~
      pathPrefix("workspaces" / Segment / Segment / "snapshots" / Segment) { (namespace, name, snapshotId) =>
        pathEnd {
          get {
            passthrough(SnapshotApiService.dataRepoSnapshotURL(namespace, name, snapshotId), HttpMethods.GET)
          } ~
          delete {
            passthrough(SnapshotApiService.dataRepoSnapshotURL(namespace, name, snapshotId), HttpMethods.DELETE)
          }
        }
      }
    }
}
