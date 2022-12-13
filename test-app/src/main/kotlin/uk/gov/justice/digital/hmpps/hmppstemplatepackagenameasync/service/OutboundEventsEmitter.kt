package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import uk.gov.justice.hmpps.sqs.HmppsTopicService
import uk.gov.justice.hmpps.sqs.MissingTopicException

@Service
class OutboundEventsEmitter(hmppsTopicService: HmppsTopicService, private val objectMapper: ObjectMapper) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  private val outboundTopic = hmppsTopicService.findByTopicId("outboundtopic") ?: throw MissingTopicException("Could not find topic outboundtopic")

  fun sendEvent(hmppsEvent: HmppsEvent) {
    when (hmppsEvent.type) {
      "offender.movement.reception", "test.type" -> publishToOutboundTopic(hmppsEvent)
      else -> "Ignoring event of type ${hmppsEvent.type}"
    }
  }

  private fun publishToOutboundTopic(hmppsEvent: HmppsEvent) {
    outboundTopic.snsClient.publish(
      PublishRequest.builder()
        .topicArn(outboundTopic.arn)
        .message(objectMapper.writeValueAsString(hmppsEvent))
        .messageAttributes(
          mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(hmppsEvent.type).build())
        )
        .build()
        .also { log.info("Published event $hmppsEvent to outbound topic") }
    )
  }
}
