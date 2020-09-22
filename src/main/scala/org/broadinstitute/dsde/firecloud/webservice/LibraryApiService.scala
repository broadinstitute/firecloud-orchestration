package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.DsdeHttpDAO
import org.broadinstitute.dsde.firecloud.model.DataUse._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{Curator, UserInfo}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, LibraryService, OntologyService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

trait LibraryApiService extends FireCloudRequestBuilding
  with FireCloudDirectives with StandardUserInfoDirectives with DsdeHttpDAO {

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
              val extReq = Get(Uri(consentUrl).withQuery(Uri.Query(("name"->orspId))))

              executeRequestRaw(userInfo.accessToken)(extReq).map { x =>
                x
                //requestContext.complete(x.)
              }
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
                          complete { libraryServiceConstructor(userInfo).UpdateLibraryMetadata(namespace, name, rawAttrsString, doValidate) }
                        }
                      }
                    } ~ {
                      get {
                        complete { libraryServiceConstructor(userInfo).GetLibraryMetadata(namespace, name) }
                      }
                    }
                  } ~
                    path("discoverableGroups") {
                      respondWithJSON {
                        put {
                          entity(as[Seq[String]]) { newGroups =>
                            complete { libraryServiceConstructor(userInfo).UpdateDiscoverableByGroups(namespace, name, newGroups) }
                          }
                        } ~
                          get {
                            complete { libraryServiceConstructor(userInfo).GetDiscoverableByGroups(namespace, name) }
                          }
                      }
                    } ~
                    path("published") {
                      post {
                        complete { libraryServiceConstructor(userInfo).SetPublishAttribute(namespace, name, true) }
                      } ~
                        delete {
                          complete { libraryServiceConstructor(userInfo).SetPublishAttribute(namespace, name, false) }
                        }
                    }
                } ~
                path("admin" / "reindex") {
                  post {
                    respondWithJSON {
                      complete { libraryServiceConstructor(userInfo).IndexAll }
                    }
                  }
                } ~
                pathPrefix("search") {
                  pathEndOrSingleSlash {
                    post {
                      respondWithJSON {
                        entity(as[LibrarySearchParams]) { params =>
                          complete { libraryServiceConstructor(userInfo).FindDocuments(params) }
                        }
                      }
                    }
                  }
                } ~
                pathPrefix("suggest") {
                  pathEndOrSingleSlash {
                    post {
                      respondWithJSON {
                        entity(as[LibrarySearchParams]) { params =>
                          complete { libraryServiceConstructor(userInfo).Suggest(params) }
                        }
                      }
                    }
                  }
                } ~
                pathPrefix("populate" / "suggest" / Segment ) { (field) =>
                  get {
                    parameter('q) { text =>
                      respondWithJSON {
                        complete { libraryServiceConstructor(userInfo).PopulateSuggest(field, text) }
                      }
                    }
                  }
                }
            }
        }
      }
}
