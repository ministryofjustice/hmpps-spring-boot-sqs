package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import org.springframework.jms.annotation.JmsListener
import org.springframework.stereotype.Service

@Service
class MessageListener(private val messageService: MessageService) {

  @JmsListener(destination = "#{@'hmpps.sqs-uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config.SqsConfigProperties'.queueName}", containerFactory = "jmsListenerContainerFactory")
  fun processMessage(message: String?) {
    messageService.handleMessage(message ?: "empty message received")
  }
}
