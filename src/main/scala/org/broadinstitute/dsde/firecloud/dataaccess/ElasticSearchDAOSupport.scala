package org.broadinstitute.dsde.firecloud.dataaccess

import java.net.InetAddress

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.elasticsearch.action.{ActionRequest, ActionRequestBuilder, ActionResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import spray.http.Uri.Authority

import scala.util.{Failure, Success, Try}

trait ElasticSearchDAOSupport extends LazyLogging {

  def buildClient(servers:Seq[Authority]): TransportClient = {
    // cluster name is constant across environments; no need to add it to config
    val settings = Settings.settingsBuilder
      .put("cluster.name", "firecloud-elasticsearch")
      .build
    val addresses = servers map { server =>
      new InetSocketTransportAddress(InetAddress.getByName(server.host.address), server.port)
    }
    TransportClient.builder().settings(settings).build.addTransportAddresses(addresses: _*)
  }

  def executeESRequest[T <: ActionRequest[T], U <: ActionResponse, V <: ActionRequestBuilder[T, U, V]](req: V): U = {
    val tick = System.currentTimeMillis
    val responseTry = Try(req.get())
    val elapsed = System.currentTimeMillis - tick
    responseTry match {
      case Success(s) =>
        logger.debug(s"ElasticSearch %s request succeeded in %s ms.".format(req.getClass.getName, elapsed))
        s
      case Failure(f) =>
        logger.warn(s"ElasticSearch %s request failed in %s ms: %s".format(req.getClass.getName, elapsed, f.getMessage))
        throw new FireCloudException("ElasticSearch request failed", f)
    }
  }


  def mapping() = {
    val mapping_example = """{
      "mappings": {
        "tweet": {
          "properties": {
            "message": {
              "type": "string"
            }
          }
        }
      }
    }"""
    """{
      |  "id": "https://api.firecloud.org/schemas/library-attributedefinitions-v1",
      |  "$schema": "http://json-schema.org/draft-04/schema#",
      |  "title": "Library attribute definitions, v1",
      |  "description": "Constraints, facet definitions, and display definitions for FireCloud Library",
      |  "type": "object",
      |  "required": ["library:datasetName", "library:datasetDescription", "library:datasetCustodian",
      |               "library:datasetDepositor", "library:datasetOwner", "library:institute", "library:indication", "library:numSubjects", "library:projectName", "library:datatype",
      |               "library:dataUseRestriction", "library:studyDesign", "library:cellType"],
      |  "propertyOrder": ["library:datasetName", "library:datasetDescription", "library:datasetCustodian",
      |                    "library:datasetDepositor", "library:datasetOwner", "library:institute", "library:indication", "library:numSubjects", "library:projectName", "library:datatype",
      |                    "library:reference", "library:dataFileFormats", "library:technology", "library:profilingProtocol", "library:dataUseRestriction",
      |                    "library:dataUseORSPConsentGroupNumber", "library:dataUseBSPSampleCollectionID", "library:studyDesign", "library:cellType", "library:coverage",
      |                    "library:ethnicity", "library:primaryDiseaseSite", "library:broadInternalResearchProjectID", "library:broadInternalResearchProjectName",
      |                    "library:broadInternalCohortName", "library:broadInternalSeqProjectNumbers"],
      |  "properties": {
      |    "library:datasetName": {
      |      "type": "string",
      |      "title": "Dataset Name"
      |    },
      |    "library:datasetDescription": {
      |      "type": "string",
      |      "title": "Dataset Description",
      |      "inputHint": "Why this set was collected and what was the criteria for inclusion?"
      |    },
      |    "library:datasetCustodian": {
      |      "type": "string",
      |      "title": "Dataset Custodian",
      |      "inputHint": "e.g. Project Manager"
      |    },
      |    "library:datasetDepositor": {
      |      "type": "string",
      |      "title": "Dataset Depositor",
      |      "inputHint": "e.g. Project Manager"
      |    },
      |    "library:datasetOwner": {
      |      "type": "string",
      |      "title": "Dataset Owner",
      |      "inputHint": "e.g. Prinicipal Investigator"
      |    },
      |    "library:institute": {
      |      "type": "array",
      |      "items": { "type": "string" },
      |      "title": "Research Institute"
      |    },
      |    "library:indication": {
      |      "type": "string",
      |      "title": "Cohort Phenotype/Indication",
      |      "description": "The phenotype/indication criteria for being included as a subject in the cohort"
      |    },
      |    "library:numSubjects": {
      |      "type": "integer",
      |      "minimum": 0,
      |      "default": 0,
      |      "title": "No. of Subjects",
      |      "description": "Dataset Size",
      |      "inputHint": "Number of participants the data maps to"
      |    },
      |    "library:projectName": {
      |      "type": "string",
      |      "title": "Project/s Name",
      |      "inputHint": "e.g. TCGA, TopMed, ExAC ; tag all relevant associated projects"
      |    },
      |    "library:datatype": {
      |      "type": "array",
      |      "items": { "type": "string" },
      |      "title": "Data Type/s",
      |      "inputHint": "e.g. Whole Genome, Whole Exome, RNA-Seq ; tag all relevant"
      |    },
      |    "library:reference": {
      |      "type": "string",
      |      "title": "Genome Reference Version",
      |      "inputHint": "e.g. hg19, GRC38;  To which genome build the data was aligned, if relevant"
      |    },
      |    "library:dataFileFormats": {
      |      "type": "array",
      |      "items": { "type": "string" },
      |      "title": "Data File Formats",
      |      "inputHint": "e.g. VCF, BAM; Tag all relevant"
      |    },
      |    "library:technology": {
      |      "type": "array",
      |      "items": { "type": "string" },
      |      "title": "Profiling Instrument Type",
      |      "inputHint": "e.g. Illumina, 10X"
      |    },
      |    "library:profilingProtocol": {
      |      "type": "array",
      |      "items": { "type": "string" },
      |      "title": "Profiling Protocol"
      |    },
      |    "library:dataUseRestriction": {
      |      "type": "string",
      |      "title": "Data Use Restriction"
      |    },
      |    "library:dataUseORSPConsentGroupNumber": {
      |      "type": "string",
      |      "title": "Data Use Restriction: ORSP consent group number"
      |    },
      |    "library:dataUseBSPSampleCollectionID": {
      |      "type": "string",
      |      "title": "Data Use Restriction: BSP Sample Collection ID"
      |    },
      |    "library:studyDesign": {
      |      "type": "string",
      |      "title": "Study Design",
      |      "inputHint": "e.g Case/Control, Trio, Tumor/normal, cases only - somatic,  cases only - germline, controls"
      |    },
      |    "library:cellType": {
      |      "type": "string",
      |      "title": "Cell Type"
      |    },
      |    "library:coverage": {
      |      "type": "string",
      |      "enum": ["0-10X","11x-20x","21x-30x","31x-100x","100x-150x",">150x"],
      |      "title": "Depth of Sequencing Coverage (Average)"
      |    },
      |    "library:ethnicity": {
      |      "type": "array",
      |      "items": { "type": "string" },
      |      "title": "Ethnicity",
      |      "inputHint": "e.g. Caucasians, African-americans, Latino,East asians, South Asians, Finnish, Non-Finnish Europeans; check all relevant"
      |    },
      |    "library:primaryDiseaseSite": {
      |      "type": "string",
      |      "title": "Primary Disease Site"
      |    },
      |
      |    "library:broadInternalResearchProjectID": {
      |      "type": "string",
      |      "title": "Research Project Broad Internal ID"
      |    },
      |    "library:broadInternalResearchProjectName": {
      |      "type": "string",
      |      "title": "Research Project Broad Internal Name"
      |    },
      |    "library:broadInternalCohortName": {
      |      "type": "string",
      |      "title": "Cohort Name Broad Internal"
      |    },
      |    "library:broadInternalSeqProjectNumbers": {
      |      "type": "array",
      |      "items": { "type": "string" },
      |      "title": "Seq Project Numbers",
      |      "inputHint": "Broad internal IDs"
      |    }
      |  }
      |} """.parseJson.convertTo[ESMappings]
  }
}
