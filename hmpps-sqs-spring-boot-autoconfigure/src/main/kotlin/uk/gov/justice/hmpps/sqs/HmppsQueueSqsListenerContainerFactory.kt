package uk.gov.justice.hmpps.sqs

import io.awspring.cloud.sqs.config.Endpoint
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory
import io.awspring.cloud.sqs.listener.AbstractMessageListenerContainer
import io.awspring.cloud.sqs.listener.ContainerOptions
import io.awspring.cloud.sqs.listener.SqsMessageListenerContainer

class SqsListenerContainerFactoryMissingException(message: String) : RuntimeException(message)

data class HmppsQueueDestinationContainerFactory(
  val destination: String,
  val factory: SqsMessageListenerContainerFactory<String>,
)

class HmppsQueueSqsListenerContainerFactory(
  private val factories: List<HmppsQueueDestinationContainerFactory>,
  private val hmppsSqsProperties: HmppsSqsProperties,
) : SqsMessageListenerContainerFactory<String>() {

  override fun createContainerInstance(endpoint: Endpoint, containerOptions: ContainerOptions): SqsMessageListenerContainer<String> = factories
    .firstOrNull { endpoint.logicalNames.contains(it.destination) }
    ?.factory
    ?.createContainer(endpoint)
    ?: throw SqsListenerContainerFactoryMissingException("Unable to find sqs listener container factory for endpoint ${endpoint.logicalNames}")

  override fun configureAbstractContainer(container: AbstractMessageListenerContainer<String>, endpoint: Endpoint) {
    super.configureAbstractContainer(container, endpoint)
    val destinationNames = endpoint.logicalNames.map {
      hmppsSqsProperties.queues[it]?.queueName ?: it
    }
    // override the container queue names to be our mapped ones from the properties file rather than our ids
    container.queueNames = destinationNames
  }
}
