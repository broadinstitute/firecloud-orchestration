package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource

import scala.concurrent.{ExecutionContext, Future}


trait OntologyDAO {

  lazy val ontologySearchUrl = FireCloudConfig.Duos.baseUrl + "/search"

  def search(term: String)(implicit ec: ExecutionContext): Future[List[TermResource]]
}
