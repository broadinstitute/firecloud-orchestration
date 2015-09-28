package org.broadinstitute.dsde.firecloud

import com.typesafe.config.ConfigFactory

object FireCloudConfig {
  private val config = ConfigFactory.load()

  object Auth {
    lazy val googleClientId = sys.env.get("GOOGLE_CLIENT_ID").get
  }

  object HttpConfig {
    private val httpConfig = config.getConfig("http")
    lazy val interface = httpConfig.getString("interface")
    lazy val port = httpConfig.getInt("port")
    lazy val timeoutSeconds = httpConfig.getLong("timeoutSeconds")
  }

  object Agora {
    private val methods = config.getConfig("methods")
    lazy val baseUrl = sys.env.get("AGORA_URL_ROOT").get
    lazy val methodsPath = methods.getString("methodsPath")
    lazy val methodsBaseUrl = baseUrl + methodsPath
    lazy val configurationsPath = methods.getString("configurationsPath")
    lazy val configurationsBaseUrl = baseUrl + configurationsPath
  }

  object Rawls {
    private val workspace = config.getConfig("workspace")
    lazy val model = "/" + workspace.getString("model")
    lazy val baseUrl = sys.env.get("RAWLS_URL_ROOT").get
    lazy val entitiesPath = workspace.getString("entitiesPath")
    lazy val methodConfigsListPath = workspace.getString("methodConfigsListPath")
    lazy val getMethodConfigUrl = baseUrl + workspace.getString("getMethodConfigPath")
    lazy val methodConfigUpdatePath = workspace.getString("methodConfigUpdatePath")
    lazy val methodConfigRenamePath = workspace.getString("methodConfigRenamePath")
    lazy val listMethodConfigurationsUrl = baseUrl + methodConfigsListPath
    lazy val updateMethodConfigurationUrl = baseUrl + methodConfigUpdatePath
    lazy val renameMethodConfigurationUrl = baseUrl + methodConfigRenamePath
    lazy val copyFromMethodRepoConfigPath = workspace.getString("copyFromMethodRepoConfig")
    lazy val copyFromMethodRepoConfigUrl = baseUrl + copyFromMethodRepoConfigPath
    lazy val copyToMethodRepoConfigPath = workspace.getString("copyToMethodRepoConfig")
    lazy val copyToMethodRepoConfigUrl = baseUrl + copyToMethodRepoConfigPath
    lazy val templatePath = workspace.getString("template")
    lazy val templateUrl = baseUrl + templatePath
    lazy val importEntitiesPath = workspace.getString("importEntitiesPath")
    lazy val importEntitiesUrl = baseUrl + importEntitiesPath
    lazy val workspacesEntitiesCopyUrl = baseUrl + workspace.getString("workspacesEntitiesCopyPath")

    def entityPathFromWorkspace(namespace: String, name: String) = baseUrl + workspace.getString("entitiesPath").format(namespace, name)
    def methodConfigPathFromWorkspace(namespace: String, name: String) = baseUrl + methodConfigsListPath.format(namespace, name)
    def importEntitiesPathFromWorkspace(namespace: String, name: String) = importEntitiesUrl.format(namespace, name)
  }

}
