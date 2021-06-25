package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MessageService {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun handleMessage(message: String) {
    log.info("processed message: $message")
  }
}

@Service
class AnotherMessageService {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun handleMessage(message: String) {
    log.info("processed message: $message")
  }
}
