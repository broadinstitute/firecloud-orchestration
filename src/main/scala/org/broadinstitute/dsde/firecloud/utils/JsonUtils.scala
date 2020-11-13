package org.broadinstitute.dsde.firecloud.utils

import com.typesafe.scalalogging.LazyLogging
import spray.json.{JsArray, JsValue}

import scala.annotation.tailrec

class JsonUtils extends LazyLogging {

  // how close to the byte size limit should we get to be considered a good batch?
  private final val TOLERANCE = .8

  // when asking for a new chunk of elements to consider, how far should we reach towards the goal?
  private final val REACH = .6

  // calculate the number of bytes required to represent the compactPrint String representation of a JsValue.
  // TODO: is there any way to do this more efficiently? The compactPrint serialization is relatively expensive.
  def compactByteSize(input: JsValue): Int = input.compactPrint.getBytes.length

  def groupByByteSize(input:JsArray,
                      inputByteSizeLimit: Int,
                      initialHint: Int = 1,
                      byteSizeFunction: JsValue => Int = compactByteSize): Seq[JsArray] = {

    val inputSize = byteSizeFunction(input)

    if (inputSize < inputByteSizeLimit) {
      logger.warn(s"groupByByteSize short-circuiting: input is $inputSize bytes, ${percent(inputSize, inputByteSizeLimit)}% of limit $inputByteSizeLimit")
      Seq(input)
    } else {
      calculateGroups(input, inputByteSizeLimit, initialHint, byteSizeFunction)
    }

  }

  private def calculateGroups(input:JsArray,
                      inputByteSizeLimit: Int,
                      initialHint: Int = 1,
                      byteSizeFunction: JsValue => Int = compactByteSize): Seq[JsArray] = {

    val byteSizeLimit = inputByteSizeLimit * TOLERANCE

    logger.warn(s"***** calculateGroups START: ${input.elements.length} elements inbound; byteSizeLimit: $byteSizeLimit; initialHint: $initialHint; byteSizeFunction: ${byteSizeFunction.toString()}")

    @tailrec
    def takeUntil(accum: Seq[JsArray], remaining: JsArray, hint: Int): Seq[JsArray] = {
      logger.warn(s"takeUntil remaining: ${remaining.elements.length}; hint: $hint; accum: (${accum.map(_.elements.length).mkString(",")})")

      // take the next ${hint} number of elements from the remaining array
      val nextElements = remaining.elements.take(hint)

      if (nextElements.isEmpty) {
        // there are no elements remaining; return and we're done
        accum
      } else {
        val currentBatch = accum.last // the jsarray we're currently building up

        // how many bytes would our jsarray be, if we added the next chunk of elements?
        val possibleNewArray = JsArray(currentBatch.elements ++ nextElements)
        val actualByteSize = byteSizeFunction(possibleNewArray)

        // estimate per-element size by dividing actual bytesize / num elements
        val perElementSize = actualByteSize.toFloat / possibleNewArray.elements.length // without toFloat, results in an Int

        // generate a new hint value: REACH towards our limit
        val nextHint: Int = (((byteSizeLimit - actualByteSize) * REACH) / perElementSize).floor.toInt

        logger.warn(s"calculateGroups analyzing size. Batch of ${possibleNewArray.elements.length} is $actualByteSize bytes")

        if (nextHint > 0 && actualByteSize < byteSizeLimit) {
          logger.warn(s"calculateGroups: $actualByteSize is under $byteSizeLimit, continuing")
          // we can append this operation and consider a new chunk
          takeUntil(accum.dropRight(1) :+ possibleNewArray, JsArray(remaining.elements.drop(nextElements.size)), nextHint)
        } else {

          val anotherHint: Int = (((byteSizeLimit - byteSizeFunction(JsArray(nextElements))) / 2) / perElementSize).floor.toInt

          logger.warn(s"calculateGroups: $actualByteSize is over $byteSizeLimit, starting a new batch")
          // appending this operation makes the current seq too large. Don't add it to the current seq; start a new one
          takeUntil(accum :+ JsArray(nextElements), JsArray(remaining.elements.drop(nextElements.size)), anotherHint)
        }
      }
    }

    val grouped = takeUntil(Seq(JsArray()), input, initialHint)

    logger.warn(s"***** calculateGroups END: ${input.elements.length} elements inbound; ${grouped.map(_.elements.length).sum} outbound")
    grouped.foreach { arr =>
      logger.warn(s"        ***** calculateGroups batch of ${arr.elements.length} and size ${byteSizeFunction(arr)} (${percent(byteSizeFunction(arr), inputByteSizeLimit)}%)")
    }

    grouped

  }


  private def percent(numerator: Int, denominator: Int): Int = ((numerator.toFloat / denominator)*100).toInt

}
