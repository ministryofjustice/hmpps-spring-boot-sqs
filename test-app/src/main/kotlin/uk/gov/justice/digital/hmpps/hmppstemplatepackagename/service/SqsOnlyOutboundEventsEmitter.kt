package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.MissingQueueException

@Service
class SqsOnlyOutboundEventsEmitter(hmppsQueueService: HmppsQueueService, private val objectMapper: ObjectMapper) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  private val outboundQueue = hmppsQueueService.findByQueueId("outboundsqsonlyqueue") ?: throw MissingQueueException("Could not find queue outboundsqsonlyqueue")

  suspend fun sendEvent(hmppsEvent: HmppsEvent) {
    when (hmppsEvent.type) {
      "offender.movement.reception", "offender.audit.object", "offender.audit.parameter", "test.type",
      -> publishToOutboundQueue(hmppsEvent)
      else -> log.info("Ignoring event of type ${hmppsEvent.type}")
    }
  }

  private suspend fun publishToOutboundQueue(hmppsEvent: HmppsEvent) {
    outboundQueue.sqsClient.sendMessage(
      SendMessageRequest.builder()
        .queueUrl(outboundQueue.queueUrl)
        .messageBody(objectMapper.writeValueAsString(hmppsEvent))
        .messageAttributes(
          mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(hmppsEvent.type).build()),
        )
        .build()
        .also { log.info("Published event $hmppsEvent to outbound queue") },
    )
  }
}
