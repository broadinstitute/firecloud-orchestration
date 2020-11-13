package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudConfig.Rawls
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions._
import org.broadinstitute.dsde.firecloud.model.MethodRepository.AgoraConfigurationShort
import org.broadinstitute.dsde.firecloud.model.Metrics.AdminStats
import org.broadinstitute.dsde.firecloud.model.MetricsFormat.AdminStatsFormat
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impRawlsBillingProjectMember, _}
import org.broadinstitute.dsde.firecloud.model.Project.ProjectRoles.ProjectRole
import org.broadinstitute.dsde.firecloud.model.Project.{RawlsBillingProjectMember, RawlsBillingProjectMembership}
import org.broadinstitute.dsde.firecloud.model.{EntityUpdateDefinition, _}
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectiveUtils
import org.broadinstitute.dsde.firecloud.utils.{JsonUtils, RestJsonClient}
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations._
import org.broadinstitute.dsde.rawls.model.StatusJsonSupport._
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.rawls.model.{StatusCheckResponse => RawlsStatus, SubsystemStatus => RawlsSubsystemStatus, _}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.joda.time.DateTime
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/**
  * Created by davidan on 9/23/16.
  */
class HttpRawlsDAO(implicit val system: ActorSystem, implicit val materializer: Materializer, implicit val executionContext: ExecutionContext)
  extends RawlsDAO with RestJsonClient with SprayJsonSupport {

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest(Get(rawlsAdminUrl))(userInfo) flatMap { response =>
      response.status match {
        case OK => Future.successful(true)
        case NotFound => Future.successful(false)
        case _ => {
          FCErrorReport(response).flatMap { errorReport =>
            Future.failed(new FireCloudExceptionWithErrorReport(errorReport))
          }
        }
      }
    }
  }

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest(Get(rawlsCuratorUrl))(userInfo) flatMap { response =>
      response.status match {
        case OK => Future.successful(true)
        case NotFound => Future.successful(false)
        case _ => {
          FCErrorReport(response).flatMap { errorReport =>
            Future.failed(new FireCloudExceptionWithErrorReport(errorReport))
          }
        }
      }
    }
  }

  override def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse] =
    authedRequestToObject[BucketUsageResponse](Get(rawlsBucketUsageUrl(ns, name)))

  override def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]] =
    authedRequestToObject[Seq[WorkspaceListResponse]](Get(rawlsWorkpacesUrl), label = Some("HttpRawlsDAO.getWorkspaces"))

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] =
    authedRequestToObject[WorkspaceResponse](Get(getWorkspaceUrl(ns, name)))

  override def patchWorkspaceAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails] =
    authedRequestToObject[WorkspaceDetails](Patch(getWorkspaceUrl(ns, name), attributeOperations))

  override def updateLibraryAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails] =
    authedRequestToObject[WorkspaceDetails](Patch(getWorkspaceUrl(ns, name) + "/library", attributeOperations))

  override def getWorkspaceACL(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceACL] =
    authedRequestToObject[WorkspaceACL](Get(getWorkspaceAclUrl(ns, name)))

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate], inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList] =
    authedRequestToObject[WorkspaceACLUpdateResponseList](Patch(patchWorkspaceAclUrl(ns, name, inviteUsersNotFound), aclUpdates))

  // you must be an admin to execute this method
  override def getAllLibraryPublishedWorkspaces(implicit userToken: WithAccessToken): Future[Seq[WorkspaceDetails]] = {
    userAuthedRequest(Get(rawlsAdminWorkspaces)).flatMap { response =>
      if(response.status.isSuccess()) {
        Unmarshal(response).to[Seq[WorkspaceDetails]].map { srw =>
          logger.info("admin workspace list reindexing: " + srw.length + " published workspaces")
          srw
        }
      }
      else {
        logger.info(s"body of reindex error response: ${response.entity}")
        throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Could not unmarshal: " + response.entity))
      }
    }
  }

  override def adminStats(startDate: DateTime, endDate: DateTime, workspaceNamespace: Option[String], workspaceName: Option[String]): Future[AdminStats] = {
    val queryParams =
      Map("startDate" -> startDate.toString, "endDate" -> endDate.toString) ++
        workspaceNamespace.map("workspaceNamespace" -> _) ++
        workspaceName.map("workspaceName" -> _)
    val url = Uri(FireCloudConfig.Rawls.authUrl + "/admin/statistics").withQuery(Query.apply(queryParams))
    adminAuthedRequestToObject[AdminStats](Get(url)) recover {
      case e: Exception =>
        logger.error(s"HttpRawlsDAO.adminStats failed with ${e.getMessage}")
        throw e
    }
  }

  override def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[Entity]] = {
    authedRequestToObject[Seq[Entity]](Get(rawlsEntitiesOfTypeUrl(workspaceNamespace, workspaceName, entityType)), true)
  }

  override def queryEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery)(implicit userToken: UserInfo): Future[EntityQueryResponse] = {
    val targetUri = FireCloudConfig.Rawls.entityQueryUriFromWorkspaceAndQuery(workspaceNamespace, workspaceName, entityType, Some(query))
    authedRequestToObject[EntityQueryResponse](Get(targetUri), compressed = true)
  }

  override def getEntityTypes(workspaceNamespace: String, workspaceName: String)(implicit userToken: UserInfo): Future[Map[String, EntityTypeMetadata]] = {
    val url = encodeUri(FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName))
    authedRequestToObject[Map[String, EntityTypeMetadata]](Get(url), compressed = true)
  }

  private def getWorkspaceUrl(ns: String, name: String) = encodeUri(FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/$ns/$name")

  private def getWorkspaceCloneUrl(ns: String, name: String) = encodeUri(FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/$ns/$name/clone")

  private def getWorkspaceAclUrl(ns: String, name: String) = encodeUri(rawlsWorkspaceACLUrl(ns, name))

  private def patchWorkspaceAclUrl(ns: String, name: String, inviteUsersNotFound: Boolean) = rawlsWorkspaceACLUrl(ns, name) + rawlsWorkspaceACLQuerystring.format(inviteUsersNotFound)

  private def workspaceCatalogUrl(ns: String, name: String) = encodeUri(FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/$ns/$name/catalog")

  override def getCatalog(ns: String, name: String)(implicit userToken: WithAccessToken): Future[Seq[WorkspaceCatalog]] =
    authedRequestToObject[Seq[WorkspaceCatalog]](Get(workspaceCatalogUrl(ns, name)), true)

  override def patchCatalog(ns: String, name: String, catalogUpdates: Seq[WorkspaceCatalog])(implicit userToken: WithAccessToken): Future[WorkspaceCatalogUpdateResponseList] =
    authedRequestToObject[WorkspaceCatalogUpdateResponseList](Patch(workspaceCatalogUrl(ns, name), catalogUpdates), true)

  // If we ever need to getAllMethodConfigs, that's Uri(rawlsWorkspaceMethodConfigsUrl.format(ns, name)).withQuery("allRepos" -> "true")
  override def getAgoraMethodConfigs(ns: String, name: String)(implicit userToken: WithAccessToken): Future[Seq[AgoraConfigurationShort]] = {
    authedRequestToObject[Seq[AgoraConfigurationShort]](Get(rawlsWorkspaceMethodConfigsUrl(ns, name)), true)
  }

  override def getProjects(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMembership]] = {
    authedRequestToObject[Seq[RawlsBillingProjectMembership]](Get(FireCloudConfig.Rawls.authUrl + "/user/billing"))
  }

  override def getProjectMembers(projectId: String)(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMember]] = {
    authedRequestToObject[Seq[RawlsBillingProjectMember]](Get(FireCloudConfig.Rawls.authUrl + s"/billing/$projectId/members"), true)
  }

  override def addUserToBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean] = {
    val url = editBillingMembershipURL(projectId, role, email)

    userAuthedRequest(Put(url), true) flatMap { resp =>
      if (resp.status.isSuccess) {
        Future.successful(true)
      } else {
        FCErrorReport(resp).flatMap { errorReport =>
          Future.failed(new FireCloudExceptionWithErrorReport(errorReport))
        }
      }
    }
  }

  override def removeUserFromBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean] = {
    val url = editBillingMembershipURL(projectId, role, email)

    userAuthedRequest(Delete(url), true) flatMap { resp =>
      if (resp.status.isSuccess) {
        Future.successful(true)
      } else {
        FCErrorReport(resp).flatMap { errorReport =>
          Future.failed(new FireCloudExceptionWithErrorReport(errorReport))
        }
      }
    }
  }

  private def aggregateResponses(requests: Seq[HttpRequest])(implicit userToken: UserInfo): Future[HttpResponse] = {

    case class ResponseStub(statusCode: StatusCode, bodyString: String)

    // send all of the batches. This needs to be done serially to ensure that prior entities are committed before
    // any entities that depend on them.
    // If we are certain these are all the same entityType, they could be sent in parallel. But, sending in parallel
    // could cause contention, so ...
    val responsesFuture: Future[List[ResponseStub]] = requests.foldLeft(Future(List.empty[ResponseStub])) { (prevFuture, next) =>
      for {
        prevResponses <- prevFuture
        nextResponse <- userAuthedRequest(next)
        strictResponse <- nextResponse.toStrict(Duration.create(2, "seconds"))
      } yield {
        // get entity as String. We use Strings here because we're not sure what shape we'll get back; it could
        // be different for successes vs. errors, for instance
        val respString = strictResponse.entity match {
          case HttpEntity.Strict(_, data) =>
            val body = data.utf8String
            logger.warn(s"got response: ${nextResponse.status.intValue()} $body")
            body
          case x =>
            logger.warn(s"WE DID NOT GET A STRICT RESPONSE: ${nextResponse.toString}")
            ""
        }
        nextResponse.entity.discardBytes()
        prevResponses :+ ResponseStub(nextResponse.status, respString)
      }
    }

    val aggregateResponse: Future[HttpResponse] = responsesFuture.map { responses =>
      if (responses.forall(_.statusCode.isSuccess())) {

        logger.warn(s"all requests succeeded! ${responses.map(_.statusCode.intValue).mkString(",")}")

        // all requests succeeded, we are good! Use the last response (this maintains pre-existing behavior)
        HttpResponse(responses.last.statusCode, entity=HttpEntity(responses.last.bodyString))
      } else {
        // find status codes
        val respCodes = responses.map(_.statusCode).distinct
        val aggregateStatus = if (respCodes.length == 1)
          respCodes.head
        else
          InternalServerError

        logger.warn(s"had failures; using aggregate status $aggregateStatus from ${responses.map(_.statusCode.intValue).mkString(",")}")

        // collect errors
        // TODO: make this more elegant. Collect inner causes in a map with outer causes?
        val responseErrors:List[ErrorReport] = responses.flatMap { stub =>
          val errOpt = Try(stub.bodyString.parseJson.convertTo[ErrorReport]).toOption
          errOpt.map { err =>
            err.copy(stackTrace = Seq(), causes = err.causes.map(inner => inner.copy(stackTrace = Seq(), causes = Seq())))
          }
        }

        // now aggregate inner causes to outer causes
        val innerOuter: List[ErrorReport] = responseErrors
          .groupBy(_.message)
          .map {
            case (message, errs) => ErrorReport(message, errs.flatMap(_.causes.map(c => ErrorReport(c.message))))
          }.toList

        logger.warn(s"collected ${innerOuter.length} response errors: $innerOuter")

        val aggregateErrors = if (innerOuter.isEmpty) {
          List(ErrorReport("Error writing entities. No other information is available."))
        } else {
          innerOuter
        }

        logger.warn(s"using aggregateErrors: $aggregateErrors")

        HttpResponse(aggregateStatus, entity=HttpEntity(ContentTypes.`application/json`, aggregateErrors.toJson.prettyPrint))

      }
    }

    aggregateResponse
  }


  private def upsertOrUpdateEntities(urlSuffix: String, workspaceNamespace: String, workspaceName: String, entityType: String, operations: Seq[EntityUpdateDefinition])(implicit userToken: UserInfo): Future[HttpResponse] = {
    // calculate the target URL, based on if this an upsert or an update
    val reqUrl = FireCloudDirectiveUtils.encodeUri(Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName) + "/" + urlSuffix)

    // total payload size limit (bytes)
    // TODO: this should correlate to the actual limit set in Rawls
    val totalSizeLimit = 256 * 1024 // 256k

    // convert the EntityUpdateDefinition to json
    val opJson: JsArray = operations.toJson match {
      case a:JsArray => a
      case _ =>
        // this should never happen
        throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "inbound EntityUpdateDefinitions did not serialize to JsArray"))
    }

    // divide the json into batches under the target payload size
    val batches: Seq[JsArray] = new JsonUtils().groupByByteSize(opJson, totalSizeLimit)
    val requests: Seq[HttpRequest] = batches.map { batch =>
      Post(reqUrl, HttpEntity(MediaTypes.`application/json`,batch.compactPrint))
    }

    logger.warn(s"upsertOrUpdateEntities using batches of: (${batches.map(_.elements.length).mkString(",")})")

    if (requests.length == 1) {
      // if there's only one batch, take the simple path to send the request and return a response
      userAuthedRequest(requests.head)
    } else {
      // we have multiple batches, send them all and return an aggregate response
      aggregateResponses(requests)
    }
  }

  override def batchUpsertEntities(workspaceNamespace: String, workspaceName: String, entityType: String, upserts: Seq[EntityUpdateDefinition])(implicit userToken: UserInfo): Future[HttpResponse] = {
    upsertOrUpdateEntities("batchUpsert", workspaceNamespace, workspaceName, entityType, upserts)
  }

  override def batchUpdateEntities(workspaceNamespace: String, workspaceName: String, entityType: String, updates: Seq[EntityUpdateDefinition])(implicit userToken: UserInfo): Future[HttpResponse] = {
    upsertOrUpdateEntities("batchUpdate", workspaceNamespace, workspaceName, entityType, updates)
  }


  private def editBillingMembershipURL(projectId: String, role: ProjectRole, email: String) = {
    FireCloudConfig.Rawls.authUrl + s"/billing/$projectId/${role.toString}/${java.net.URLEncoder.encode(email, "UTF-8")}"
  }

  override def status: Future[SubsystemStatus] = {
    val rawlsStatus = unAuthedRequestToObject[RawlsStatus](Get(Uri(FireCloudConfig.Rawls.baseUrl).withPath(Uri.Path("/status"))))

    def parseRawlsMessages(rs: RawlsStatus): Option[List[String]] = {
      val rawlsMessages = rs.systems.toList.flatMap {
        case (k, RawlsSubsystemStatus(subsystem, Some(messages))) if messages.nonEmpty =>
          Some(s"$k: ${messages.mkString(", ")}")
        case _ => None
      }
      if (rawlsMessages.nonEmpty) Some(rawlsMessages) else None
    }

    rawlsStatus.map { status =>
      SubsystemStatus(status.ok, parseRawlsMessages(status))
    }.recoverWith { case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Some(StatusCodes.InternalServerError) =>
      // Rawls returns 500 on status check failures, but the JSON data should still be sent in the
      // response body and stored in the ErrorReport. Try to parse a RawlsStatus from the error report
      // (if it exists) so we can display it to the user. If this fails, then we will recover from the error below.
      Future(e.errorReport.message.parseJson.convertTo[RawlsStatus]).map { recoveredStatus =>
        SubsystemStatus(recoveredStatus.ok, parseRawlsMessages(recoveredStatus))
      }
    }.recover {
      case NonFatal(e) => SubsystemStatus(false, Some(List(e.getMessage)))
    }
  }

  override def deleteWorkspace(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[WorkspaceDeleteResponse] = {
    authedRequestToObject[WorkspaceDeleteResponse](Delete(getWorkspaceUrl(workspaceNamespace, workspaceName)))
  }

  override def cloneWorkspace(workspaceNamespace: String, workspaceName: String, cloneRequest: WorkspaceRequest)(implicit userToken: WithAccessToken): Future[WorkspaceDetails] = {
    authedRequestToObject[WorkspaceDetails](Post(getWorkspaceCloneUrl(workspaceNamespace, workspaceName), cloneRequest))
  }

}
