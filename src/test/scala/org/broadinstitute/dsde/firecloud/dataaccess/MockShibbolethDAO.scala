package org.broadinstitute.dsde.firecloud.dataaccess

import scala.concurrent.Future

class MockShibbolethDAO extends ShibbolethDAO {
  val publicKey = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsDPAkAwpWiO2659gPsIj\nzx9IypuiInn2F4IaCCJSxtjqRNw5g6QPJeMVjmnn3jT8CCMzvoOIOq8n7rmyog/p\npjJpq4AcVA0GjV8Nz7cWF/VwR+e/mN5CGvY4OfnCTBi5PUmywGLZMcJNhcbnka69\nexL18WwnM0d6/A/LYcmCQcI+YuakDksGAdrOn74WOrKQFa78SVOnB6Mfpf65rmu7\nTMQ66JBUuM2vIW+P1p4//+9MBSKUoGyXkbOsykBc1XYn/lLRoDCf2onYDTGjdILh\n7eSXdi6+VzgQ7j3hdkSRSj+mN2Vmq/AEWHd1lc/OQDMcRcEnRPyhwny9VW0gehyt\nWwIDAQAB\n-----END PUBLIC KEY-----"
  override def getPublicKey(): Future[String] = {
    Future.successful(publicKey)
  }
}
