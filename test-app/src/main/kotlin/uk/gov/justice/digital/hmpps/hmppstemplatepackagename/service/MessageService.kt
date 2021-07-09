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
    outboundEventsEmitter.sendEvent(hmppsEvent)
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
