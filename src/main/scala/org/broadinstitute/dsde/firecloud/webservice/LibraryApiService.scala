package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpMethods, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.DataUse._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{Curator, UserInfo, _}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, LibraryService, OntologyService}
import org.broadinstitute.dsde.firecloud.utils.{EnabledUserDirectives, RestJsonClient, StandardUserInfoDirectives}
import spray.json.DefaultJsonProtocol._

import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext

trait LibraryApiService extends FireCloudDirectives
  with StandardUserInfoDirectives with EnabledUserDirectives
  with RestJsonClient {

  implicit val executionContext: ExecutionContext

  lazy val rawlsCuratorUrl = FireCloudConfig.Rawls.authUrl + "/user/role/curator"

  val libraryServiceConstructor: UserInfo => LibraryService
  val ontologyServiceConstructor: () => OntologyService

  val consentUrl = FireCloudConfig.Duos.baseConsentUrl + "/api/consent"

  val libraryRoutes: Route =
    pathPrefix("duos") {
      path("autocomplete" / Segment) { (searchTerm) =>
        get {
          complete { ontologyServiceConstructor().autocompleteOntology(searchTerm) }
        }
      } ~
        path("researchPurposeQuery") {
          post {
            entity(as[ResearchPurposeRequest]) { researchPurposeRequest =>
              complete { ontologyServiceConstructor().buildResearchPurposeQuery(researchPurposeRequest) }
            }
          }
        } ~
        path("structuredData") {
          post {
            entity(as[StructuredDataRequest]) { request =>
              complete { ontologyServiceConstructor().buildStructuredUseRestrictionAttribute(request) }
            }
          }
        }
    } ~
      pathPrefix("schemas") {
        path("library-attributedefinitions-v1") {
          withResourceFileContents(LibraryService.schemaLocation) { jsonContents =>
            complete(StatusCodes.OK, jsonContents)
          }
        }
      } ~
      pathPrefix("api") {
        requireUserInfo() { userInfo =>
          path("duos" / "consent" / "orsp" / Segment) { (orspId) =>
            get {
              //note: not a true passthrough, slight manipulation of the query params here
              passthrough(Uri(consentUrl).withQuery(Uri.Query(("name"->orspId))), HttpMethods.GET)
            }
          } ~
          pathPrefix("library") {
            path("user" / "role" / "curator") {
              get { requestContext =>
                userAuthedRequest(Get(rawlsCuratorUrl))(userInfo).flatMap { response =>
                  response.status match {
                    case OK => requestContext.complete(OK, Curator(true))
                    case NotFound => requestContext.complete(OK, Curator(false))
                    case _ => requestContext.complete(response) // replay the root exception
                  }
                }
              }
            } ~
            path("groups") {
              pathEndOrSingleSlash {
                get {
                  requireEnabledUser(userInfo) {
                    complete(OK, FireCloudConfig.ElasticSearch.discoverGroupNames.asScala.toSeq)
                  }
                }
              }
            } ~
            pathPrefix(Segment / Segment) { (namespace, name) =>
              path("metadata") {
                put {
                  parameter("validate" ? "false") { validationParam =>
                    val doValidate = java.lang.Boolean.valueOf(validationParam) // for lenient parsing
                    entity(as[String]) { rawAttrsString =>
                      complete { libraryServiceConstructor(userInfo).updateLibraryMetadata(namespace, name, rawAttrsString, doValidate) }
                    }
                  }
                } ~ {
                  get {
                    complete { libraryServiceConstructor(userInfo).getLibraryMetadata(namespace, name) }
                  }
                }
              } ~
              path("discoverableGroups") {
                put {
                  entity(as[Seq[String]]) { newGroups =>
                    complete { libraryServiceConstructor(userInfo).updateDiscoverableByGroups(namespace, name, newGroups) }
                  }
                } ~
                  get {
                    complete { libraryServiceConstructor(userInfo).getDiscoverableByGroups(namespace, name) }
                  }
              } ~
              path("published") {
                  post {
                    complete { libraryServiceConstructor(userInfo).setWorkspaceIsPublished(namespace, name, true) }
                  } ~
                  delete {
                    complete { libraryServiceConstructor(userInfo).setWorkspaceIsPublished(namespace, name, false) }
                  }
                }
            } ~
            path("admin" / "reindex") {
              post {
                complete { libraryServiceConstructor(userInfo).adminIndexAllWorkspaces() }
              }
            } ~
            pathPrefix("search") {
              pathEndOrSingleSlash {
                post {
                  entity(as[LibrarySearchParams]) { params =>
                    complete { libraryServiceConstructor(userInfo).findDocuments(params) }
                  }
                }
              }
            } ~
            pathPrefix("suggest") {
              pathEndOrSingleSlash {
                post {
                  entity(as[LibrarySearchParams]) { params =>
                    complete { libraryServiceConstructor(userInfo).suggest(params) }
                  }
                }
              }
            } ~
            pathPrefix("populate" / "suggest" / Segment ) { (field) =>
              get {
                requireEnabledUser(userInfo) {
                  parameter(Symbol("q")) { text =>
                    complete {
                      libraryServiceConstructor(userInfo).populateSuggest(field, text)
                    }
                  }
                }
              }
            }
          }
        }
      }
}
