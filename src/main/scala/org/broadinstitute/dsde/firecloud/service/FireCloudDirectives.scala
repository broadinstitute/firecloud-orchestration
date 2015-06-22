package org.broadinstitute.dsde.firecloud.service

import spray.http.MediaTypes._

trait FireCloudDirectives extends spray.routing.Directives {
    def respondWithJSON = respondWithMediaType(`application/json`)
}
