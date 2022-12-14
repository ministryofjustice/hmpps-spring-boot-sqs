package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class InboundMessageService(private val outboundEventsEmitter: OutboundEventsEmitter) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun handleMessage(hmppsEvent: HmppsEvent) {
    log.info("received event: $hmppsEvent")
    val outboundEventType = when (hmppsEvent.type) {
      "OFFENDER_MOVEMENT-RECEPTION" -> "offender.movement.reception"
      "OFFENDER_MOVEMENT-DISCHARGE" -> "offender.movement.discharge"
      else -> hmppsEvent.type
    }
    outboundEventsEmitter.sendEvent(HmppsEvent(hmppsEvent.id, outboundEventType, hmppsEvent.contents))
  }
}

@Service
class OutboundMessageService {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun handleMessage(hmppsEvent: HmppsEvent) {
    log.info("received event: $hmppsEvent")
  }
}
