package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.WorkspaceAccessLevels.WorkspaceAccessLevel
import spray.json._
import spray.json.DefaultJsonProtocol._

/*
* TODO: share with rawls code, instead of copying wholesale
*/

case class WorkspaceACL(acl: Map[String, WorkspaceAccessLevel])

case class WorkspaceACLUpdate(email: String, accessLevel: WorkspaceAccessLevel)

object WorkspaceAccessLevels {
  sealed trait WorkspaceAccessLevel extends Ordered[WorkspaceAccessLevel] {
    def compare(that: WorkspaceAccessLevel) = { all.indexOf(this).compare(all.indexOf(that)) }

    override def toString = WorkspaceAccessLevels.toString(this)
    def withName(name: String) = WorkspaceAccessLevels.withName(name)
  }

  case object NoAccess extends WorkspaceAccessLevel
  case object Read extends WorkspaceAccessLevel
  case object Write extends WorkspaceAccessLevel
  case object Owner extends WorkspaceAccessLevel
  case object ProjectOwner extends WorkspaceAccessLevel

  val all = Seq(NoAccess, Read, Write, Owner, ProjectOwner)
  val groupAccessLevelsAscending = Seq(Read, Write, Owner, ProjectOwner)

  // note that the canonical string must match the format for GCS ACL roles,
  // because we use it to set the role of entities in the ACL.
  // (see https://cloud.google.com/storage/docs/json_api/v1/bucketAccessControls)
  def toString(v: WorkspaceAccessLevel): String = {
    v match {
      case ProjectOwner => "PROJECT_OWNER"
      case Owner => "OWNER"
      case Write => "WRITER"
      case Read => "READER"
      case NoAccess => "NO ACCESS"
      case _ => throw new DeserializationException(s"invalid WorkspaceAccessLevel [${v}]")
    }
  }

  def withName(s: String): WorkspaceAccessLevel = {
    s match {
      case "PROJECT_OWNER" => ProjectOwner
      case "OWNER" => Owner
      case "WRITER" => Write
      case "READER" => Read
      case "NO ACCESS" => NoAccess
      case _ => throw new DeserializationException(s"invalid WorkspaceAccessLevel [${s}]")
    }
  }

  def max(a: WorkspaceAccessLevel, b: WorkspaceAccessLevel): WorkspaceAccessLevel = {
    if( a <= b ) {
      b
    } else {
      a
    }
  }
}

object WorkspaceACLJsonSupport {
  implicit object WorkspaceAccessLevelFormat extends RootJsonFormat[WorkspaceAccessLevel] {
    override def write(value: WorkspaceAccessLevel): JsValue = JsString(value.toString)
    override def read(json: JsValue): WorkspaceAccessLevel = json match {
      case JsString(name) => WorkspaceAccessLevels.withName(name)
      case x => throw new DeserializationException("invalid value: " + x)
    }
  }

  implicit val WorkspaceACLFormat = jsonFormat1(WorkspaceACL)

  implicit val WorkspaceACLUpdateFormat = jsonFormat2(WorkspaceACLUpdate)
}
