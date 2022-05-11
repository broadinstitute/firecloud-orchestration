package org.broadinstitute.dsde.firecloud.dataaccess

import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ElasticSearch._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.PerformanceLogging
import spray.json._

import scala.util.{Failure, Success, Try}

trait ElasticSearchDAOSupport extends LazyLogging with PerformanceLogging {

  def elasticSearchRequest[T](extraLogging: String = "")(req: => T): T = {
    val tick = Instant.now()
    val responseTry = Try(req)
    val tock = Instant.now()
    responseTry match {
      case Success(s) =>
        perfLogger.info(perfmsg(req.getClass.getSimpleName, s"success $extraLogging", tick, tock))
        s
      case Failure(f) =>
        perfLogger.info(perfmsg(req.getClass.getSimpleName, s"failure $extraLogging", tick, tock))
        logger.warn(s"ElasticSearch ${req.getClass.getName} request failed in ${tock.toEpochMilli-tick.toEpochMilli} ms: ${f.getMessage} $extraLogging")
        throw new FireCloudException("ElasticSearch request failed", f)
    }
  }

//  def makeMapping(attributeJson: String): String = {
//    // generate mappings from the Library schema file
//    val definition = attributeJson.parseJson.convertTo[AttributeDefinition]
//    val attributeDetailMap = definition.properties filter(_._2.indexable.getOrElse(true)) map {
//      case (label: String, detail: AttributeDetail) => createType(label, detail)
//    }
//    /* add the additional mappings that aren't tracked in the schema file:
//     *   - _suggest property for autocomplete
//     *   - _discoverableByGroups property to hold discover-mode permissions
//     *   - parents.order and parents.label for ontology-aware search
//     */
//    val addlMappings:Map[String, ESPropertyFields] = Map(
//      fieldSuggest -> ESType.suggestField("text"),
//      fieldDiscoverableByGroups -> ESInternalType("text"),
//      fieldOntologyParents -> ESNestedType(Map(
//        fieldOntologyParentsLabel -> ESInnerField("text", include_in_all=Some(false), copy_to=Some(ElasticSearch.fieldSuggest)),
//        fieldOntologyParentsOrder -> ESInnerField("integer", include_in_all=Some(false))
//      ))
//    )
//    val props = attributeDetailMap ++ addlMappings
//    ESDatasetProperty(props).toJson.prettyPrint
//  }
//
//  def createType(label: String, detail: AttributeDetail): (String, ESPropertyFields) = {
//    val itemType = detail match {
//      case x if x.`type` == "array" && x.items.isDefined => x.items.get.`type`
//      case _ => detail.`type`
//    }
//    val searchSuggest = itemType == "text"
//    val createSuggest = detail.typeahead.contains("populate")
//    val isAggregate = detail match {
//      case x if x.aggregate.isDefined => true
//      case _ => false
//    }
//    label -> ESType(itemType, createSuggest, searchSuggest, isAggregate)
//  }
}
