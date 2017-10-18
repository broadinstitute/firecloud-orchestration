package org.broadinstitute.dsde.firecloud.service

import org.parboiled.common.FileUtils
import spray.http.{HttpMethod, Uri}
import spray.http.MediaTypes._
import spray.routing._

import scala.util.Try

object FireCloudDirectiveUtils {
  def encodeUri(path: String): String = {
    val pattern = """(https|http)://([^/\r\n]+?)(:\d+)?(/[^\r\n]*)?""".r

    def toUri(url: String) = url match {
      case pattern(theScheme, theHost, thePort, thePath) =>
        val p: Int = Try(thePort.replace(":","").toInt).toOption.getOrElse(0)
        Uri.from(scheme = theScheme, port = p, host = theHost, path = thePath)
    }
    toUri(path).toString
  }
}

trait FireCloudDirectives extends spray.routing.Directives with PerRequestCreator with spray.httpx.RequestBuilding {
  def respondWithJSON = respondWithMediaType(`application/json`)

  def passthrough(unencodedPath: String, methods: HttpMethod*): Route =
    passthrough(Uri(unencodedPath), methods:_*)

  def passthrough(requestCompression: Boolean, unencodedPath: String, methods: HttpMethod*): Route =
    passthrough(Uri(unencodedPath), methods:_*)

  def passthrough(uri: Uri, methods: HttpMethod*): Route = methods map { inMethod =>
    generateExternalHttpPerRequestForMethod(requestCompression = true, uri, inMethod)
  } reduce (_ ~ _)


  @deprecated("Makes routing confusing!","2017-08-29")
  def passthroughAllPaths(ourEndpointPath: String, targetEndpointUrl: String, requestCompression: Boolean = true): Route = {
    pathPrefix(separateOnSlashes(ourEndpointPath) ) {
      extract(_.request.method) { httpMethod =>
        unmatchedPath { remaining =>
          parameterMap { params =>
            passthrough(requestCompression, Uri(encodeUri(targetEndpointUrl + remaining)).withQuery(params).toString, httpMethod)
          }
        }
      }
    }
  }

  def encodeUri(path: String): String = FireCloudDirectiveUtils.encodeUri(path)

  private def generateExternalHttpPerRequestForMethod(requestCompression: Boolean, uri: Uri, inMethod: HttpMethod) = {
    val outMethod = new RequestBuilder(inMethod)
    // POST, PUT, PATCH
    if (inMethod.isEntityAccepted) {
      method(inMethod) {
        respondWithJSON { requestContext =>
          externalHttpPerRequest(requestCompression, requestContext, outMethod(uri, requestContext.request.entity))
        }
      }
    }
    else {
      // GET, DELETE
      method(inMethod) { requestContext =>
        externalHttpPerRequest(requestCompression, requestContext, outMethod(uri))
      }
    }
  }

  // convert our standard set of OAuth parameters path / prompt / callback to the form suitable for generating the callback
  def oauthParams(innerRoute: (String, String) => Route): Route = parameters("path".?, "prompt".?, "callback".?) { (userpath, prompt, callback) =>

    /*
     * prompt:    either "force" or "auto". Anything else will be treated as "auto".
     *              "force" always requests a new refresh token, prompting the user for offline permission.
     *              "auto" allows Google to determine if it thinks the user needs a new refresh token,
     *                which is not necessarily in sync with rawls' cache.
     *
     * callback:  once OAuth is done and we have an access token from Google, to what hostname should we
     *              return the browser? Example: "https://portal.firecloud.org/"
     * path:      once OAuth is done and we have an access token from Google, to what fragment should we
     *              return the browser? Example: "workspaces"
     *
     * The FireCloud UI typically calls /login with a callback parameter containing the hostname of the UI.
     *  It does not typically pass a path parameter.
     *  It never passes a prompt parameter; this exists to allow developers to call /login manually to
     *    get a new refresh token.
     */

    // create a hash-delimited string of the callback+path and pass into the state param.
    // the state param is defined by and required by the OAuth standard, and we override its typical
    // usage here to pass the UI's hostname/fragment through the OAuth dance.
    // TODO: future story: generate and persist unique security token along with the callback/path
    val state = callback.getOrElse("") + "#" + userpath.getOrElse("")

    // if the user requested "force" then "force"; if the user specified something else, or nothing, use "auto".
    // this allows the end user/UI to control when we force-request a new refresh token. Default to auto,
    // to allow Google to decide; we'll verify in our token store after this step completes.
    val approvalPrompt = prompt match {
      case Some("force") => "force"
      case _ => "auto"
    }

    innerRoute(state, approvalPrompt)
  }

  def withResourceFileContents(path: String)(innerRoute: String => Route): Route =
    innerRoute( FileUtils.readAllTextFromResource(path) )

}
