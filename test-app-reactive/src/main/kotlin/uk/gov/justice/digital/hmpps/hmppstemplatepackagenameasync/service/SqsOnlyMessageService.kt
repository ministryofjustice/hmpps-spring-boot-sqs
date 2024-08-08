package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.hmpps.sqs.audit.HmppsAuditEvent
import uk.gov.justice.hmpps.sqs.audit.HmppsAuditService

@Service
class SqsOnlyInboundMessageService(
  private val outboundEventsEmitter: SqsOnlyOutboundEventsEmitter,
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
      "OFFENDER_AUDIT-OBJECT" -> "offender.audit.object"
      "OFFENDER_AUDIT-PARAMETER" -> "offender.audit.parameter"
      else -> hmppsEvent.type
    }
    if (outboundEventType == "offender.audit.object") {
      hmppsAuditService.publishEvent(
        HmppsAuditEvent(
          what = "important event",
          who = "me",
          service = "my-special-test-app",
        ),
      )
    }
    if (outboundEventType == "offender.audit.parameter") {
      hmppsAuditService.publishEvent(what = "important event", who = "me")
    }
    outboundEventsEmitter.sendEvent(HmppsEvent(hmppsEvent.id, outboundEventType, hmppsEvent.contents))
  }
}

@Service
class SqsOnlyOutboundMessageService(
  private val outboundEventsEmitter: SqsOnlyOutboundTestEventsEmitter,
) {
  private companion object {
    private val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  suspend fun handleMessage(hmppsEvent: HmppsEvent) {
    log.info("received event: {}", hmppsEvent)
    outboundEventsEmitter.sendEvent(hmppsEvent)
  }
}
