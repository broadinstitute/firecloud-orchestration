package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.UserInfo

import scala.concurrent.Future


trait OntologyDAO {

  lazy val ontologySearchUrl = FireCloudConfig.Duos.baseUrl + "/search"

  def search(term: String)(implicit userInfo: UserInfo): Future[List[TermResource]]
}
