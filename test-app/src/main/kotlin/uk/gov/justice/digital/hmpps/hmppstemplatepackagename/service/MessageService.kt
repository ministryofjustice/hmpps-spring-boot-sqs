package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.hmpps.sqs.audit.HmppsAuditEvent
import uk.gov.justice.hmpps.sqs.audit.HmppsAuditService

@Service
class InboundMessageService(
  private val outboundEventsEmitter: OutboundEventsEmitter,
  private val hmppsAuditService: HmppsAuditService,
) {
  private companion object {
    private val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  suspend fun handleMessage(hmppsEvent: HmppsEvent) {
    log.info("received event: {}", hmppsEvent)
    val outboundEventType = when (hmppsEvent.type) {
      "OFFENDER_MOVEMENT-RECEPTION" -> "offender.movement.reception"
      "OFFENDER_MOVEMENT-DISCHARGE" -> "offender.movement.discharge"
      "OFFENDER_MOVEMENT-IMPORTANT" -> "offender.movement.important"
      else -> hmppsEvent.type
    }
    if (outboundEventType == "offender.movement.important") {
      hmppsAuditService.publishEvent(
        HmppsAuditEvent(
          what = "important event",
          who = "me",
          service = "test-app",
        ),
      )
    }
    outboundEventsEmitter.sendEvent(HmppsEvent(hmppsEvent.id, outboundEventType, hmppsEvent.contents))
  }
}

@Service
class OutboundMessageService {
  private companion object {
    private val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun handleMessage(hmppsEvent: HmppsEvent) {
    log.info("received event: {}", hmppsEvent)
  }
}
