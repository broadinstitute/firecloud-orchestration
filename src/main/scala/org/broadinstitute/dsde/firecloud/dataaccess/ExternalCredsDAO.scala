package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.databiosphere.workspacedata.client.ApiException

import scala.concurrent.Future

trait ExternalCredsDAO {

  @throws(classOf[ApiException])
  def getVisas(provider: String,
               userId: String,
               issuer: String,
               visaType: String,
               orchInfo: WithAccessToken
  ): Future[Seq[AnyRef]]
}
