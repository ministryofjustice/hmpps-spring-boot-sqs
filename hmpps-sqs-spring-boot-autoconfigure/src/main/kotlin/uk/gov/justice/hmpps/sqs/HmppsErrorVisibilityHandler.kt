package uk.gov.justice.hmpps.sqs

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.awspring.cloud.sqs.listener.QueueMessageVisibility
import io.awspring.cloud.sqs.listener.SqsHeaders
import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue

class HmppsErrorVisibilityHandler(
  private val objectMapper: ObjectMapper,
  private val hmppsSqsdProperties: HmppsSqsProperties,
) {
  fun setErrorVisibilityTimeout(message: Message<Any>, queueId: String) {
    val eventType = getEventType(message)
    val receiveCount = (message.headers["Sqs_Msa_ApproximateReceiveCount"] as String).toInt()
    val sqsVisibility = message.headers["Sqs_VisibilityTimeout"] as QueueMessageVisibility

    val timeouts = hmppsSqsdProperties.queues[queueId]
      ?.let { queue ->
        queue.eventErrorVisibilityTimeout?.get(eventType)
          ?: queue.errorVisibilityTimeout
      }
      ?: hmppsSqsdProperties.defaultErrorVisibilityTimeout

    val nextTimeoutSeconds = when {
      receiveCount <= timeouts.size -> timeouts[receiveCount - 1]
      timeouts.isNotEmpty() -> timeouts.last()
      else -> 0
    }
    log.info("Setting error visibility timeout for event type {} on queue {} with receive count {} to {} seconds", eventType, queueId, receiveCount, nextTimeoutSeconds)
    sqsVisibility.changeTo(nextTimeoutSeconds)
  }

  private fun getEventType(message: Message<in Any>): String? {
    val payload = message.payload as? String
    return if (payload?.contains("MessageAttributes") == true) {
      val attributes = objectMapper.readValue(
        objectMapper.readTree(payload).at("/MessageAttributes").traverse(),
        object : TypeReference<MutableMap<String, MessageAttribute>>() {},
      )
      attributes?.get("eventType")?.Value as String?
    } else {
      null
    }
      ?: extractAttributes(message)?.let { it["eventType"] }?.stringValue()
  }

  private fun extractAttributes(message: Message<Any>): MutableMap<String, MessageAttributeValue>? {
    val headers = message.headers[SqsHeaders.SQS_SOURCE_DATA_HEADER] as? software.amazon.awssdk.services.sqs.model.Message
    return headers?.messageAttributes() ?: run {
      null
    }
  }

  private class MessageAttribute(
    @param:JsonProperty("Type") val Type: String,
    @param:JsonProperty("Value") val Value: Any?,
  )

  companion object {
    private val log = LoggerFactory.getLogger(HmppsErrorVisibilityHandler::class.java)
  }
}
