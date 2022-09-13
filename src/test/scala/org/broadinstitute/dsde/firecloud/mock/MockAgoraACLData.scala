package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.ACLNames._
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.{AgoraPermission, EntityAccessControlAgora, FireCloudPermission, Method}

/**
 * Created by davidan on 10/29/15.
 */
object MockAgoraACLData {

  val standardPermsPath = "/ns/standard/1/permissions"
  val withEdgeCasesPath = "/ns/edges/1/permissions"

  private val email = Some("davidan@broadinstitute.org")

  // FC PERMISSIONS
  private val ownerFC = FireCloudPermission("owner@broadinstitute.org", Owner)
  private val readerFC = FireCloudPermission("reader@broadinstitute.org", Reader)
  private val noAccessFC = FireCloudPermission("noaccess@broadinstitute.org", NoAccess)
  // AGORA PERMISSIONS
  private val allAgora = AgoraPermission(Some("owner@broadinstitute.org"), Some(ListAll))
  private val ownerAgora = AgoraPermission(Some("owner@broadinstitute.org"), Some(ListOwner))
  private val readerAgora = AgoraPermission(Some("reader@broadinstitute.org"), Some(ListReader))
  private val noAccessAgora = AgoraPermission(Some("noaccess@broadinstitute.org"), Some(ListNoAccess))
  // AGORA EDGE CASES
  private val partialsAgora = AgoraPermission(Some("agora-partial@broadinstitute.org"), Some(List("Read","Write")))
  private val extrasAgora = AgoraPermission(Some("agora-extras@broadinstitute.org"), Some(ListOwner ++ List("Extra","Permissions")))
  private val emptyAgora = AgoraPermission(Some("agora-empty@broadinstitute.org"), Some(List("")))
  private val noneAgora = AgoraPermission(Some("agora-none@broadinstitute.org"), None)
  private val emptyUserAgora = AgoraPermission(Some(""), Some(ListOwner))
  private val noneUserAgora = AgoraPermission(None, Some(ListOwner))

  // standardAgora translates to standardFC.
  // standardFC translates to translatedStandardAgora
  val standardAgora = List(allAgora, readerAgora, ownerAgora, noAccessAgora)
  val standardFC = List(ownerFC, readerFC, ownerFC, noAccessFC)

  val translatedStandardAgora = List(ownerAgora, readerAgora, ownerAgora, noAccessAgora)

  val edgesAgora = standardAgora ++ List(partialsAgora, extrasAgora, emptyAgora, noneAgora, emptyUserAgora, noneUserAgora)

  // multi-permissions endpoint response
  val multiUpsertResponse: List[EntityAccessControlAgora] = List(
    EntityAccessControlAgora(Method(Some("ns1"),Some("n1"),Some(1)), Seq(AgoraPermission(Some("user1@example.com"), Some(ListAll))), None),
    EntityAccessControlAgora(Method(Some("ns2"),Some("n2"),Some(2)), Seq(AgoraPermission(Some("user2@example.com"), Some(ListReader))), None),
    EntityAccessControlAgora(Method(Some("ns3"),Some("n3"),Some(3)), Seq.empty[AgoraPermission], Some("this is an error message"))
  )


}
