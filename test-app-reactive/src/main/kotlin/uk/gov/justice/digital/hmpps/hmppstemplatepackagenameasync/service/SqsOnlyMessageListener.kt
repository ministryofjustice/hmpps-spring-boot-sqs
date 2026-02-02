package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service

import io.awspring.cloud.sqs.annotation.SqsListener
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.util.concurrent.CompletableFuture

@Service
class SqsOnlyInboundMessageListener(private val inboundMessageService: SqsOnlyInboundMessageService, private val jsonMapper: JsonMapper) {

  /** Example of a listener that takes a String and manually converts into its own type. */
  @SqsListener("inboundsqsonlyqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String): CompletableFuture<Void?> {
    val event: HmppsEvent = jsonMapper.readValue(message)
    return CoroutineScope(Context.current().asContextElement()).future {
      inboundMessageService.handleMessage(event)
      null
    }
  }
}

@Service
class SqsOnlyOutboundMessageListener(private val outboundMessageService: SqsOnlyOutboundMessageService) {

  /**
   * Example of a listener that gets the library to do the conversion into its own type.
   *
   * The queue in this example is not linked to a topic so therefore the payload comes through direct in the message
   * field and doesn't need to be unwrapped.
   */
  @SqsListener("outboundsqsonlyqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: HmppsEvent): CompletableFuture<Void?> = CoroutineScope(Context.current().asContextElement()).future {
    outboundMessageService.handleMessage(message)
    null
  }
}
