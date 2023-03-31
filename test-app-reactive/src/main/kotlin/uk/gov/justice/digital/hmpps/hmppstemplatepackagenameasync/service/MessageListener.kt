package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.awspring.cloud.sqs.annotation.SqsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class InboundMessageListener(private val inboundMessageService: InboundMessageService, private val objectMapper: ObjectMapper) {

  @SqsListener("inboundqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: Message): CompletableFuture<Void> {
    val event = objectMapper.readValue(message.Message, HmppsEvent::class.java)
    return CoroutineScope(Dispatchers.Default).future {
      inboundMessageService.handleMessage(event)
    }.thenAccept {}
  }
}

@Service
class OutboundMessageListener(private val outboundMessageService: OutboundMessageService, private val objectMapper: ObjectMapper) {

  @SqsListener("outboundqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: Message) {
    val event = objectMapper.readValue(message.Message, HmppsEvent::class.java)
    outboundMessageService.handleMessage(event)
  }
}

data class HmppsEvent(val id: String, val type: String, val contents: String)
data class EventType(val Value: String, val Type: String)
data class MessageAttributes(val eventType: EventType)
data class Message(
  val Message: String,
  val MessageId: String,
  val MessageAttributes: MessageAttributes,
)
