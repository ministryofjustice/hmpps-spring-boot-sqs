package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import org.springframework.jms.annotation.JmsListener
import org.springframework.stereotype.Service

@Service
class MessageListener(private val messageService: MessageService) {

  @JmsListener(destination = "mainQueue", containerFactory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String?) {
    messageService.handleMessage(message ?: "empty message received")
  }
}

@Service
class AnotherMessageListener(private val anotherMessageService: AnotherMessageService) {

  @JmsListener(destination = "anotherQueue", containerFactory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String?) {
    anotherMessageService.handleMessage(message ?: "empty message received")
  }
}
