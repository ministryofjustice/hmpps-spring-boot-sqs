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
class SqsOnlyOutboundMessageListener(private val outboundMessageService: SqsOnlyOutboundMessageService, private val jsonMapper: JsonMapper) {

  @SqsListener("outboundsqsonlyqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String): CompletableFuture<Void?> {
    val event: HmppsEvent = jsonMapper.readValue(message)
    return CoroutineScope(Context.current().asContextElement()).future {
      outboundMessageService.handleMessage(event)
      null
    }
  }
}

data class SqsHmppsEvent(val id: String, val type: String, val contents: String)
