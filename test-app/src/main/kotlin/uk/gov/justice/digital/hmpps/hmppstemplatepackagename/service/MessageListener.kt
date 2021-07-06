package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import org.springframework.jms.annotation.JmsListener
import org.springframework.stereotype.Service

@Service
class MessageListener(private val messageService: MessageService) {

  @JmsListener(destination = "mainqueue", containerFactory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String?) {
    messageService.handleMessage(message ?: "empty message received for mainqueue")
  }
}

@Service
class AnotherMessageListener(private val anotherMessageService: AnotherMessageService) {

  @JmsListener(destination = "anotherqueue", containerFactory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String?) {
    anotherMessageService.handleMessage(message ?: "empty message received for anotherqueue")
  }
}
