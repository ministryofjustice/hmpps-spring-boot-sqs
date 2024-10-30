package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.awspring.cloud.sqs.annotation.SqsListener
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class InboundMessageListener(private val inboundMessageService: InboundMessageService, private val objectMapper: ObjectMapper) {

  @SqsListener("inboundqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: Message): CompletableFuture<Void?> {
    val event = objectMapper.readValue(message.Message, HmppsEvent::class.java)
    return CoroutineScope(Context.current().asContextElement()).future {
      inboundMessageService.handleMessage(event)
      null
    }
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
data class MessageAttribute(val Value: String, val Type: String)
typealias EventType = MessageAttribute
class MessageAttributes() : HashMap<String, MessageAttribute>() {
  constructor(attribute: EventType) : this() {
    put(attribute.Value, attribute)
  }
}
data class Message(
  val Message: String,
  val MessageId: String,
  val MessageAttributes: MessageAttributes,
)
