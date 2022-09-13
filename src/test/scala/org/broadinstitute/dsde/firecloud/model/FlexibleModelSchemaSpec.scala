package org.broadinstitute.dsde.firecloud.model

import org.scalatest.freespec.AnyFreeSpec

class FlexibleModelSchemaSpec extends AnyFreeSpec {

  val schema = FlexibleModelSchema

  "ModelSchema.isAttributeArray" - {

    "should be false for various scalars" in {
      List("", "-1", "0", "1", "hello", "{}", "true", "false",
        "null", "123.45", "[", "]", "][", "[-]") foreach { input =>
        withClue(s"for input '$input', should return false") {
          assert(!schema.isAttributeArray(input))
        }
      }
    }

    "should be true for various arrays" in {
      List("[]", "[1]", "[1,2,3]", "[true]", "[true,false]", "[true,1,null]",
        """["foo"]""", """["foo","bar"]""", """["white",     "space"]""",
        """["foo",1,true,null]""",
        """[{}, [{},{},{"a":"b"},true], "foo"]"""
      ) foreach { input =>
        withClue(s"for input '$input', should return true") {
          assert(schema.isAttributeArray(input))
        }
      }
    }

  }
}
