package uk.gov.justice.hmpps.sqs

import org.springframework.jms.support.destination.DynamicDestinationResolver
import javax.jms.Destination
import javax.jms.Session

class HmppsQueueDestinationResolver(private val hmppsSqsProperties: HmppsSqsProperties) : DynamicDestinationResolver() {

  override fun resolveDestinationName(session: Session?, destinationName: String, pubSubDomain: Boolean): Destination {
    val destination = hmppsSqsProperties.queues[destinationName]?.queueName ?: destinationName
    return super.resolveDestinationName(session, destination, pubSubDomain)
  }
}
