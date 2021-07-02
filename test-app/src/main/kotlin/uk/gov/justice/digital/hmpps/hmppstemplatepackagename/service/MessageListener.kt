package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import org.springframework.jms.annotation.JmsListener
import org.springframework.stereotype.Service

@Service
class MessageListener(private val messageService: MessageService) {

  @JmsListener(destination = "#{@'hmpps.sqs-uk.gov.justice.hmpps.sqs.HmppsQueueProperties'.queues['mainQueue'].queueName}", containerFactory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String?) {
    messageService.handleMessage(message ?: "empty message received")
  }
}

@Service
class AnotherMessageListener(private val anotherMessageService: AnotherMessageService) {

  @JmsListener(destination = "#{@'hmpps.sqs-uk.gov.justice.hmpps.sqs.HmppsQueueProperties'.queues['anotherQueue'].queueName}", containerFactory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String?) {
    anotherMessageService.handleMessage(message ?: "empty message received")
  }
}
