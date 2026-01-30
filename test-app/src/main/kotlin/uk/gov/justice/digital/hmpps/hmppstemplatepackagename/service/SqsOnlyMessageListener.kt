package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service

import io.awspring.cloud.sqs.annotation.SqsListener
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

@Service
class SqsOnlyInboundMessageListener(private val inboundMessageService: SqsOnlyInboundMessageService) {

  /**
   * Example of a listener that gets the library to do the conversion into its own type.
   *
   * The queue in this example is not linked to a topic so therefore the payload comes through direct in the message
   * field and doesn't need to be unwrapped.
   */
  @SqsListener("inboundsqsonlyqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: HmppsEvent) = inboundMessageService.handleMessage(message)
}

@Service
class SqsOnlyOutboundMessageListener(private val outboundMessageService: SqsOnlyOutboundMessageService, private val jsonMapper: JsonMapper) {

  /** Example of a listener that takes a String and manually converts into its own type. */
  @SqsListener("outboundsqsonlyqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: String) {
    val event: HmppsEvent = jsonMapper.readValue(message)
    outboundMessageService.handleMessage(event)
  }
}
