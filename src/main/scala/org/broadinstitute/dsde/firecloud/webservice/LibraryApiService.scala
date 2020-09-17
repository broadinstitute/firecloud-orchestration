package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.DataUse._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{Curator, UserInfo}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, LibraryService, OntologyService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
//import spray.client.pipelining._
//import spray.http.StatusCodes._
//import spray.http.Uri
//import spray.httpx.SprayJsonSupport._
import spray.json._
//import spray.routing._
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

trait LibraryApiService extends FireCloudRequestBuilding
  with FireCloudDirectives with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext

  lazy val rawlsCuratorUrl = FireCloudConfig.Rawls.authUrl + "/user/role/curator"

  val libraryServiceConstructor: UserInfo => LibraryService
  val ontologyServiceConstructor: () => OntologyService

  val consentUrl = FireCloudConfig.Duos.baseConsentUrl + "/api/consent"

  val libraryRoutes: Route =
    pathPrefix("duos") {
      path("autocomplete" / Segment) { (searchTerm) =>
        get {
          complete { ontologyServiceConstructor().AutocompleteOntology(searchTerm) }
        }
      } ~
        path("researchPurposeQuery") {
          post {
            respondWithJSON {
              entity(as[ResearchPurposeRequest]) { researchPurposeRequest =>
                complete { ontologyServiceConstructor().ResearchPurposeQuery(researchPurposeRequest) }
              }
            }
          }
        } ~
        path("structuredData") {
          post {
            respondWithJSON {
              entity(as[StructuredDataRequest]) { request =>
                complete { ontologyServiceConstructor().DataUseLimitation(request) }
              }
            }
          }
        }
    } ~
      pathPrefix("schemas") {
        path("library-attributedefinitions-v1") {
          respondWithJSON {
            withResourceFileContents(LibraryService.schemaLocation) { jsonContents =>
              complete(StatusCodes.OK, jsonContents)
            }
          }
        }
      } ~
      pathPrefix("api") {
        requireUserInfo() { userInfo =>
          path("duos" / "consent" / "orsp" / Segment) { (orspId) =>
            get { requestContext =>
              val extReq = Get(Uri(consentUrl).withQuery(("name", orspId)))
              externalHttpPerRequest(requestContext, extReq)
            }
          } ~
            pathPrefix("library") {
              path("user" / "role" / "curator") {
                get { requestContext =>
                  val pipeline = authHeaders(requestContext) ~> sendReceive
                  pipeline {
                    Get(rawlsCuratorUrl)
                  } map { response =>
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
                      respondWithJSON {
                        requestContext =>
                          requestContext.complete(OK, FireCloudConfig.ElasticSearch.discoverGroupNames.asScala.toSeq)
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
                          requestContext =>
                            perRequest(requestContext,
                              LibraryService.props(libraryServiceConstructor, userInfo),
                              LibraryService.UpdateLibraryMetadata(namespace, name, rawAttrsString, doValidate))
                        }
                      }
                    } ~ {
                      get {
                        requestContext =>
                          perRequest(requestContext,
                            LibraryService.props(libraryServiceConstructor, userInfo),
                            LibraryService.GetLibraryMetadata(namespace, name))
                      }
                    }
                  } ~
                    path("discoverableGroups") {
                      respondWithJSON {
                        put {
                          entity(as[Seq[String]]) { newGroups =>
                            requestContext =>
                              perRequest(requestContext,
                                LibraryService.props(libraryServiceConstructor, userInfo),
                                LibraryService.UpdateDiscoverableByGroups(namespace, name, newGroups))
                          }
                        } ~
                          get {
                            requestContext =>
                              perRequest(requestContext,
                                LibraryService.props(libraryServiceConstructor, userInfo),
                                LibraryService.GetDiscoverableByGroups(namespace, name))
                          }
                      }
                    } ~
                    path("published") {
                      post { requestContext =>
                        perRequest(requestContext,
                          LibraryService.props(libraryServiceConstructor, userInfo),
                          LibraryService.SetPublishAttribute(namespace, name, true))
                      } ~
                        delete { requestContext =>
                          perRequest(requestContext,
                            LibraryService.props(libraryServiceConstructor, userInfo),
                            LibraryService.SetPublishAttribute(namespace, name, false))
                        }
                    }
                } ~
                path("admin" / "reindex") {
                  post {
                    respondWithJSON { requestContext =>
                      perRequest(requestContext,
                        LibraryService.props(libraryServiceConstructor, userInfo),
                        LibraryService.IndexAll)
                    }
                  }
                } ~
                pathPrefix("search") {
                  pathEndOrSingleSlash {
                    post {
                      respondWithJSON {
                        entity(as[LibrarySearchParams]) { params => requestContext =>
                          perRequest(requestContext,
                            LibraryService.props(libraryServiceConstructor, userInfo),
                            LibraryService.FindDocuments(params))
                        }
                      }
                    }
                  }
                } ~
                pathPrefix("suggest") {
                  pathEndOrSingleSlash {
                    post {
                      respondWithJSON {
                        entity(as[LibrarySearchParams]) { params => requestContext =>
                          perRequest(requestContext,
                            LibraryService.props(libraryServiceConstructor, userInfo),
                            LibraryService.Suggest(params))
                        }
                      }
                    }
                  }
                } ~
                pathPrefix("populate" / "suggest" / Segment ) { (field) =>
                  get {
                    parameter('q) { text =>
                      respondWithJSON {
                        requestContext =>
                          perRequest(requestContext,
                            LibraryService.props(libraryServiceConstructor, userInfo),
                            LibraryService.PopulateSuggest(field, text))
                      }
                    }
                  }
                }
            }
        }
      }
}
