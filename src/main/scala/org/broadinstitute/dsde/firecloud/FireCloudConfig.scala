package org.broadinstitute.dsde.firecloud

import com.typesafe.config.ConfigFactory

object FireCloudConfig {
  private val config = ConfigFactory.load()
  private val configurations = ConfigFactory.load("configurations.conf")
  private val merged = config.withFallback(configurations)

  object Auth {
    private val auth = merged.getConfig("auth")
    lazy val googleClientId = sys.env.getOrElse("GOOGLE_CLIENT_ID", auth.getString("googleClientId"))
  }

  object HttpConfig {
    private val httpConfig = merged.getConfig("http")
    lazy val interface = httpConfig.getString("interface")
    lazy val port = httpConfig.getInt("port")
    lazy val timeoutSeconds = httpConfig.getLong("timeoutSeconds")
  }

  object Agora {
    private val methods = merged.getConfig("methods")
    lazy val baseUrl = methods.getString("baseUrl")
    lazy val methodsPath = methods.getString("methodsPath")
    lazy val methodsListUrl = baseUrl + methodsPath
    lazy val configurationsPath = methods.getString("configurationsPath")
    lazy val configurationsListUrl = baseUrl + configurationsPath
  }

  object Rawls {
    private val workspace = merged.getConfig("workspace")
    lazy val model = "/" + workspace.getString("model")
    lazy val baseUrl= workspace.getString("baseUrl")
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
    lazy val importEntitiesPath = workspace.getString("importEntitiesPath")
    lazy val importEntitiesUrl = baseUrl + importEntitiesPath
    lazy val submissionsPath = workspace.getString("submissionsPath")
    lazy val submissionByIdPath = workspace.getString("submissionByIdPath")
    lazy val workflowOutputsByIdPath = workspace.getString("workflowOutputsByIdPath")

    def entityPathFromWorkspace(namespace: String, name: String) = baseUrl + workspace.getString("entitiesPath").format(namespace, name)
    def methodConfigPathFromWorkspace(namespace: String, name: String) = baseUrl + methodConfigsListPath.format(namespace, name)
    def importEntitiesPathFromWorkspace(namespace: String, name: String) = importEntitiesUrl.format(namespace, name)
    def submissionsUrl(namespace: String, name: String) = baseUrl + submissionsPath.format(namespace, name)
    def submissionByIdUrl(namespace: String, name: String, id: String) = baseUrl + submissionByIdPath.format(namespace, name, id)
    def workflowOutputsByIdUrl(namespace: String, name: String, submissionId: String, workflowId: String) =
      baseUrl + workflowOutputsByIdPath.format(namespace, name, submissionId, workflowId)
  }

}
