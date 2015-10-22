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
    lazy val authPrefix = methods.getString("authPrefix")
    lazy val authUrl = baseUrl + authPrefix
    lazy val methodsPath = methods.getString("methodsPath")
    lazy val methodsBaseUrl = authUrl + methodsPath
    lazy val configurationsPath = methods.getString("configurationsPath")
    lazy val configurationsBaseUrl = authUrl + configurationsPath
  }

  object Rawls {
    private val workspace = config.getConfig("workspace")
    lazy val model = "/" + workspace.getString("model")
    lazy val baseUrl = sys.env.get("RAWLS_URL_ROOT").get
    lazy val authPrefix = workspace.getString("authPrefix")
    lazy val authUrl = baseUrl + authPrefix
    lazy val workspacesPath = workspace.getString("workspacesPath")
    lazy val workspacesUrl = authUrl + workspacesPath
    lazy val entitiesPath = workspace.getString("entitiesPath")
    lazy val methodConfigsListPath = workspace.getString("methodConfigsListPath")
    lazy val getMethodConfigUrl = authUrl + workspace.getString("getMethodConfigPath")
    lazy val getMethodConfigValidationUrl = authUrl + workspace.getString("getMethodConfigValidationPath")
    lazy val methodConfigUpdatePath = workspace.getString("methodConfigUpdatePath")
    lazy val methodConfigRenamePath = workspace.getString("methodConfigRenamePath")
    lazy val listMethodConfigurationsUrl = authUrl + methodConfigsListPath
    lazy val updateMethodConfigurationUrl = authUrl + methodConfigUpdatePath
    lazy val renameMethodConfigurationUrl = authUrl + methodConfigRenamePath
    lazy val methodConfigPath = workspace.getString("methodConfigPath")
    lazy val methodConfigUrl = authUrl + workspace.getString("methodConfigPath")
    lazy val copyFromMethodRepoConfigPath = workspace.getString("copyFromMethodRepoConfig")
    lazy val copyFromMethodRepoConfigUrl = authUrl + copyFromMethodRepoConfigPath
    lazy val copyToMethodRepoConfigPath = workspace.getString("copyToMethodRepoConfig")
    lazy val copyToMethodRepoConfigUrl = authUrl + copyToMethodRepoConfigPath
    lazy val templatePath = workspace.getString("template")
    lazy val templateUrl = authUrl + templatePath
    lazy val importEntitiesPath = workspace.getString("importEntitiesPath")
    lazy val workspacesEntitiesCopyPath = workspace.getString("workspacesEntitiesCopyPath")
    lazy val workspacesEntitiesCopyUrl = authUrl + workspacesEntitiesCopyPath

    def entityPathFromWorkspace(namespace: String, name: String) = authUrl + entitiesPath.format(namespace, name)
    def methodConfigPathFromWorkspace(namespace: String, name: String) = authUrl + methodConfigsListPath.format(namespace, name)
    def importEntitiesPathFromWorkspace(namespace: String, name: String) = authUrl + importEntitiesPath.format(namespace, name)
  }

  object Thurloe {
    private val profile = config.getConfig("userprofile")
    lazy val baseUrl = sys.env.get("THURLOE_URL_ROOT").get
    lazy val setKey = profile.getString("setKey")
    lazy val getAll = profile.getString("getAll")
    lazy val get = profile.getString("get")
    lazy val delete = profile.getString("delete")
  }

}
