package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.applicationinsights.TelemetryClient
import kotlinx.coroutines.future.await
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sns.model.PublishRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.config.trackEvent
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.MissingTopicException
import uk.gov.justice.hmpps.sqs.eventTypeMessageAttributes

@Service
class OutboundEventsEmitter(
  hmppsQueueService: HmppsQueueService,
  private val objectMapper: ObjectMapper,
  private val telemetryClient: TelemetryClient,
) {
  private companion object {
    private val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  private val outboundTopic = hmppsQueueService.findByTopicId("outboundtopic") ?: throw MissingTopicException("Could not find topic outboundtopic")

  suspend fun sendEvent(hmppsEvent: HmppsEvent) {
    when (hmppsEvent.type) {
      "offender.movement.reception", "offender.audit.object", "offender.audit.parameter", "test.type",
      -> publishToOutboundTopic(hmppsEvent)
      else -> log.info("Ignoring event of type ${hmppsEvent.type}")
    }
  }

  private suspend fun publishToOutboundTopic(hmppsEvent: HmppsEvent) {
    outboundTopic.snsClient.publish(
      PublishRequest.builder()
        .topicArn(outboundTopic.arn)
        .message(objectMapper.writeValueAsString(hmppsEvent))
        .eventTypeMessageAttributes(hmppsEvent.type)
        .build()
        .also { log.info("Published event $hmppsEvent to outbound topic") },
    ).await().also {
      telemetryClient.trackEvent(
        hmppsEvent.type,
        mapOf("messageId" to it.messageId()),
      )
    }
  }
}
