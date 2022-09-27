package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig.Rawls.{submissionQueueStatusUrl, workspacesUrl}
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import org.broadinstitute.dsde.firecloud.utils.StreamingPassthrough

trait SubmissionApiService extends FireCloudDirectives with StreamingPassthrough {
  val submissionServiceRoutes: Route = {
    pathPrefix("submissions" / "queueStatus") {
      streamingPassthrough(submissionQueueStatusUrl)
    } ~
    pathPrefix("workspaces" / Segment / Segment / "submissions") { (namespace, name) =>
      // N.B. streamingPassthrough to ".../submissions" also handles ".../submissionsCount"
      streamingPassthrough(s"$workspacesUrl/${escapePathSegment(namespace)}/${escapePathSegment(name)}/submissions")
    }
  }
}
