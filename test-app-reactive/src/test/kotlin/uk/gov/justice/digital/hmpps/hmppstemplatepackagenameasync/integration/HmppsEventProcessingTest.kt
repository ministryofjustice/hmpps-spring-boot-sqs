package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mockingDetails
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.Message
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue

class HmppsEventProcessingTest : IntegrationTestBase() {

  @Test
  fun `event is published to outbound topic`() = runBlocking<Unit> {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).messageAttributes(
        mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build())
      ).build()
    )

    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

    val receivedEvent = ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()
      .let { request -> outboundTestSqsClient.receiveMessage(request).get().messages()[0].body() }
      .let { response -> objectMapper.readValue(response, Message::class.java) }
      .Message
      .let { message -> objectMapper.readValue(message, HmppsEvent::class.java) }

    assertThat(receivedEvent.id).isEqualTo("event-id")
    assertThat(receivedEvent.type).isEqualTo("offender.movement.reception")
    assertThat(receivedEvent.contents).isEqualTo("some event contents")
  }

  @Test
  fun `event is published to outbound topic but the test queue subscriber ignores it`() {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-DISCHARGE", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).messageAttributes(
        mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build())
      ).build()
    )

    await untilCallTo { mockingDetails(outboundEventsEmitterSpy).invocations!! } matches { it?.isNotEmpty() ?: false } // Don't understand why it is nullable here

    assertThat(outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get()).isEqualTo(0)
  }

  @Test
  fun `event is published to outbound topic received by queue with no dlq`() = runBlocking<Unit> {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).messageAttributes(
        mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build())
      ).build()
    )

    await untilCallTo { outboundTestNoDlqSqsClient.countMessagesOnQueue(outboundTestNoDlqQueueUrl).get() } matches { it == 1 }

    val receivedEvent = ReceiveMessageRequest.builder().queueUrl(outboundTestNoDlqQueueUrl).build()
      .let { request -> outboundTestSqsClient.receiveMessage(request).get().messages()[0].body() }
      .let { response -> objectMapper.readValue(response, Message::class.java) }
      .Message
      .let { message -> objectMapper.readValue(message, HmppsEvent::class.java) }

    assertThat(receivedEvent.id).isEqualTo("event-id")
    assertThat(receivedEvent.type).isEqualTo("offender.movement.reception")
    assertThat(receivedEvent.contents).isEqualTo("some event contents")
  }
}
