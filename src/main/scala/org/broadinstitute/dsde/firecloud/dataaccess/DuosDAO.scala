package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.UserInfo
import spray.json.JsObject

import scala.concurrent.Future


trait DuosDAO {

  lazy val ontologySearchUrl = FireCloudConfig.Duos.baseOntologyUrl + "/search"
  lazy val orspIdSearchUrl = FireCloudConfig.Duos.baseConsentUrl + "/api/consent"

  def ontologySearch(term: String): Future[Option[List[TermResource]]]

  def orspIdSearch(userInfo: UserInfo, orspId: String): Future[Option[JsObject]]

}
