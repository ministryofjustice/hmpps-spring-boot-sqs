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
class SqsOnlyInboundMessageListener(private val inboundMessageService: SqsOnlyInboundMessageService, private val objectMapper: ObjectMapper) {

  @SqsListener("inboundsqsonlyqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String): CompletableFuture<Void?> {
    val event = objectMapper.readValue(message, HmppsEvent::class.java)
    return CoroutineScope(Context.current().asContextElement()).future {
      inboundMessageService.handleMessage(event)
      null
    }
  }
}

@Service
class SqsOnlyOutboundMessageListener(private val outboundMessageService: SqsOnlyOutboundMessageService, private val objectMapper: ObjectMapper) {

  @SqsListener("outboundsqsonlyqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String): CompletableFuture<Void?> {
    val event = objectMapper.readValue(message, HmppsEvent::class.java)
    return CoroutineScope(Context.current().asContextElement()).future {
      outboundMessageService.handleMessage(event)
      null
    }
  }
}

data class SqsHmppsEvent(val id: String, val type: String, val contents: String)
