package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import com.amazonaws.services.sns.model.MessageAttributeValue
import com.amazonaws.services.sns.model.PublishRequest
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.MissingTopicException

@Service
class OutboundEventsEmitter(hmppsQueueService: HmppsQueueService, private val objectMapper: ObjectMapper) {

  private val outboundTopic = hmppsQueueService.findByTopicId("outboundtopic") ?: throw MissingTopicException("Could not find topic outboundtopic")

  fun sendEvent(hmppsEvent: HmppsEvent) {
    when (hmppsEvent.type) {
      "OFFENDER_MOVEMENT-RECEPTION", "test.type" -> publishToOutboundTopic(hmppsEvent)
      else -> "Ignoring event of type ${hmppsEvent.type}"
    }
  }

  private fun publishToOutboundTopic(hmppsEvent: HmppsEvent) {
    outboundTopic.snsClient.publish(
      PublishRequest(outboundTopic.arn, objectMapper.writeValueAsString(hmppsEvent))
        .withMessageAttributes(
          mapOf("eventType" to MessageAttributeValue().withDataType("String").withStringValue(hmppsEvent.type))
        )
    )
  }
}
