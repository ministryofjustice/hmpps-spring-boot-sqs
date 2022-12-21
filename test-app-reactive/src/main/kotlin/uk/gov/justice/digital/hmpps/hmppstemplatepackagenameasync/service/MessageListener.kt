package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.awspring.cloud.sqs.annotation.SqsListener
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class InboundMessageListener(private val inboundMessageService: InboundMessageService, private val objectMapper: ObjectMapper) {

  @SqsListener(queueNames = ["inboundqueue"], factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(rawMessage: String?): CompletableFuture<Unit> {
    val (Message) = objectMapper.readValue(rawMessage, Message::class.java)
    val event = objectMapper.readValue(Message, HmppsEvent::class.java)
    @Suppress("OPT_IN_USAGE")
    return GlobalScope.launch { inboundMessageService.handleMessage(event) }.asCompletableFuture()
  }
}

@Service
class OutboundMessageListener(private val outboundMessageService: OutboundMessageService, private val objectMapper: ObjectMapper) {

  @SqsListener(queueNames = ["outboundqueue"], factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(rawMessage: String?) {
    val (message) = objectMapper.readValue(rawMessage, Message::class.java)
    val event = objectMapper.readValue(message, HmppsEvent::class.java)
    outboundMessageService.handleMessage(event)
  }
}

data class HmppsEvent(val id: String, val type: String, val contents: String)
data class EventType(val Value: String, val Type: String)
data class MessageAttributes(val eventType: EventType)
data class Message(
  val Message: String,
  val MessageId: String,
  val MessageAttributes: MessageAttributes
)
