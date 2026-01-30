package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import io.awspring.cloud.sqs.annotation.SqsListener
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class SqsOnlyInboundMessageListener(private val inboundMessageService: SqsOnlyInboundMessageService) {

  @SqsListener("inboundsqsonlyqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: HmppsEvent): CompletableFuture<Void?> = CoroutineScope(Context.current().asContextElement()).future {
    inboundMessageService.handleMessage(message)
    null
  }
}

@Service
class SqsOnlyOutboundMessageListener(private val outboundMessageService: SqsOnlyOutboundMessageService) {

  @SqsListener("outboundsqsonlyqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: HmppsEvent): CompletableFuture<Void?> = CoroutineScope(Context.current().asContextElement()).future {
    outboundMessageService.handleMessage(message)
    null
  }
}
