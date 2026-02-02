package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service

import io.awspring.cloud.sqs.annotation.SqsListener
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import uk.gov.justice.hmpps.sqs.SnsMessage
import java.util.concurrent.CompletableFuture

@Service
class InboundMessageListener(private val inboundMessageService: InboundMessageService, private val jsonMapper: JsonMapper) {

  /** Example of a listener that takes a String and manually converts into its own type */
  @SqsListener("inboundqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String): CompletableFuture<Void?> {
    val snsMessage: SnsMessage = jsonMapper.readValue(message)
    val event: HmppsEvent = jsonMapper.readValue(snsMessage.message)
    return CoroutineScope(Context.current().asContextElement()).future {
      inboundMessageService.handleMessage(event)
      null
    }
  }
}

@Service
class OutboundMessageListener(private val outboundMessageService: OutboundMessageService, private val jsonMapper: JsonMapper) {

  /**
   * Example of a listener that gets the library to do the conversion into its own type.
   *
   * The queue in this example is linked to a topic and the messages come through with a defined structure:
   *   Type, Message, MessageId and MessageAttributes. The payload if the message is then in the Message, which comes
   *   through as a String.
   */
  @SqsListener("outboundqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: SnsMessage) {
    val event: HmppsEvent = jsonMapper.readValue(message.message)
    outboundMessageService.handleMessage(event)
  }
}

data class HmppsEvent(val id: String, val type: String, val contents: String)
