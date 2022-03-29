package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.ACLNames._
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.{AgoraPermission, FireCloudPermission}
import org.scalatest.freespec.AnyFreeSpec

class AgoraACLTranslationSpec extends AnyFreeSpec {

  val email = Some("davidan@broadinstitute.org")

  "TranslationTests" - {
    // OWNER
    "when translating Owner FC->Agora" - {
      "should equal ListOwner" in {
        val objFC = FireCloudPermission(email.get, Owner)
        val objAgora = objFC.toAgoraPermission
        assertResult(email) { objAgora.user }
        assertResult(ListOwner) { objAgora.roles.get }
      }
    }
    "when translating ListOwner Agora->FC" - {
      "should equal Owner" in {
        val objAgora = AgoraPermission(email, Some(ListOwner))
        val objFC = objAgora.toFireCloudPermission
        assertResult(email.get) { objFC.user }
        assertResult(Owner) { objFC.role}
      }
    }
    // READER
    "when translating Reader FC->Agora" - {
      "should equal ListReader" in {
        val objFC = FireCloudPermission(email.get, Reader)
        val objAgora = objFC.toAgoraPermission
        assertResult(email) { objAgora.user }
        assertResult(ListReader) { objAgora.roles.get }
      }
    }
    "when translating ListReader Agora->FC" - {
      "should equal Reader" in {
        val objAgora = AgoraPermission(email, Some(ListReader))
        val objFC = objAgora.toFireCloudPermission
        assertResult(email.get) { objFC.user }
        assertResult(Reader) { objFC.role }
      }
    }
    // NO ACCESS
    "when translating NoAccess FC->Agora" - {
      "should equal ListNoAccess" in {
        val objFC = FireCloudPermission(email.get, NoAccess)
        val objAgora = objFC.toAgoraPermission
        assertResult(email) { objAgora.user }
        assertResult(ListNoAccess) { objAgora.roles.get }
      }
    }
    "when translating ListNoAccess Agora->FC" - {
      "should equal NoAccess" in {
        val objAgora = AgoraPermission(email, Some(ListNoAccess))
        val objFC = objAgora.toFireCloudPermission
        assertResult(email.get) { objFC.user }
        assertResult(NoAccess) { objFC.role }
      }
    }
    // ALL
    "when translating ListAll Agora->FC" - {
      "should equal Owner" in {
        val objAgora = AgoraPermission(email, Some(ListAll))
        val objFC = objAgora.toFireCloudPermission
        assertResult(email.get) { objFC.user }
        assertResult(Owner) { objFC.role }
      }
    }
    // EDGE CASES, AGORA->FC
    "when translating partial list Agora->FC" - {
      "should equal NoAccess" in {
        val objAgora = AgoraPermission(email, Some(List("Read","Write")))
        val objFC = objAgora.toFireCloudPermission
        assertResult(email.get) { objFC.user }
        assertResult(NoAccess) { objFC.role }
      }
    }
    "when translating superset list Agora->FC" - {
      "should equal NoAccess" in {
        val objAgora = AgoraPermission(email, Some(ListOwner ++ List("Extra","Permissions")))
        val objFC = objAgora.toFireCloudPermission
        assertResult(email.get) { objFC.user }
        assertResult(NoAccess) { objFC.role }
      }
    }
    "when translating empty list Agora->FC" - {
      "should equal NoAccess" in {
        val objAgora = AgoraPermission(email, Some(List.empty))
        val objFC = objAgora.toFireCloudPermission
        assertResult(email.get) { objFC.user }
        assertResult(NoAccess) { objFC.role }
      }
    }
    "when translating whitespace list Agora->FC" - {
      "should equal NoAccess" in {
        val objAgora = AgoraPermission(email, Some(List("")))
        val objFC = objAgora.toFireCloudPermission
        assertResult(email.get) { objFC.user }
        assertResult(NoAccess) { objFC.role }
      }
    }
    "when translating None Agora->FC" - {
      "should equal NoAccess" in {
        val objAgora = AgoraPermission(email, None)
        val objFC = objAgora.toFireCloudPermission
        assertResult(email.get) { objFC.user }
        assertResult(NoAccess) { objFC.role }
      }
    }

    "when working with an empty user Agora" - {
      "should throw IllegalArgumentException" in {
        intercept[IllegalArgumentException] {
          val objAgora = AgoraPermission(Some(""), Some(ListOwner))
          val objFC = objAgora.toFireCloudPermission
        }
      }
    }
    "when working with a whitespace user Agora" - {
      "should throw IllegalArgumentException" in {
        intercept[IllegalArgumentException] {
          val objAgora = AgoraPermission(Some("    "), Some(ListOwner))
          val objFC = objAgora.toFireCloudPermission
        }
      }
    }
    "when working with a None user Agora" - {
      "should throw IllegalArgumentException" in {
        intercept[IllegalArgumentException] {
          val objAgora = AgoraPermission(None, Some(ListOwner))
          val objFC = objAgora.toFireCloudPermission
        }
      }
    }

    // EDGE CASES, FC->AGORA
    "when trying to work with an unknown role FC" - {
      "should throw IllegalArgumentException" in {
        intercept[IllegalArgumentException] {
          val objFC = FireCloudPermission(email.get, "OWNERtypo")
        }
      }
    }
    "when trying to work with an empty string role FC" - {
      "should throw IllegalArgumentException" in {
        intercept[IllegalArgumentException] {
          val objFC = FireCloudPermission(email.get, "")
        }
      }
    }
    "when trying to work with a whitespace role FC" - {
      "should throw IllegalArgumentException" in {
        intercept[IllegalArgumentException] {
          val objFC = FireCloudPermission(email.get, "    ")
        }
      }
    }
    "when trying to work with an empty string user FC" - {
      "should throw IllegalArgumentException" in {
        intercept[IllegalArgumentException] {
          val objFC = FireCloudPermission("", Owner)
        }
      }
    }
    "when trying to work with a whitespace user FC" - {
      "should throw IllegalArgumentException" in {
        intercept[IllegalArgumentException] {
          val objFC = FireCloudPermission("    ", Owner)
        }
      }
    }
  }

}
