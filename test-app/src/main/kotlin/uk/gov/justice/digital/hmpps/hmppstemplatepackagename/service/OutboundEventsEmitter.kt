package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sns.model.PublishRequest
import tools.jackson.databind.json.JsonMapper
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.MissingTopicException
import uk.gov.justice.hmpps.sqs.eventTypeMessageAttributes

@Service
class OutboundEventsEmitter(hmppsQueueService: HmppsQueueService, private val jsonMapper: JsonMapper) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  private val outboundTopic = hmppsQueueService.findByTopicId("outboundtopic") ?: throw MissingTopicException("Could not find topic outboundtopic")

  fun sendEvent(hmppsEvent: HmppsEvent) {
    when (hmppsEvent.type) {
      "offender.movement.reception", "offender.audit.object", "offender.audit.parameter", "test.type",
      -> publishToOutboundTopic(hmppsEvent)
      else -> log.info("Ignoring event of type ${hmppsEvent.type}")
    }
  }

  fun publishToOutboundTopic(hmppsEvent: HmppsEvent) {
    outboundTopic.snsClient.publish(
      PublishRequest.builder()
        .topicArn(outboundTopic.arn)
        .message(jsonMapper.writeValueAsString(hmppsEvent))
        .eventTypeMessageAttributes(hmppsEvent.type)
        .build()
        .also { log.info("Published event $hmppsEvent to outbound topic") },
    ).get()
  }
}
