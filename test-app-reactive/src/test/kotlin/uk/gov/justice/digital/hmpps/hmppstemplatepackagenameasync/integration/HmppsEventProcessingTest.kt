package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.Message
import uk.gov.justice.hmpps.sqs.countAllMessagesOnQueue
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import java.util.UUID

class HmppsEventProcessingTest : IntegrationTestBase() {

  @Test
  fun `event is published to outbound topic`() = runTest {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).messageAttributes(
        mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
      ).build(),
    )

    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

    val (message) = objectMapper.readValue(outboundTestSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()).get().messages()[0].body(), Message::class.java)
    val receivedEvent = objectMapper.readValue(message, HmppsEvent::class.java)

    assertThat(receivedEvent.id).isEqualTo("event-id")
    assertThat(receivedEvent.type).isEqualTo("offender.movement.reception")
    assertThat(receivedEvent.contents).isEqualTo("some event contents")
  }

  @Test
  fun `event is published to outbound topic but the test queue subscriber ignores it`() {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-DISCHARGE", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).messageAttributes(
        mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
      ).build(),
    )

    await untilCallTo { mockingDetails(outboundEventsEmitterSpy).invocations!! } matches { it?.isNotEmpty() ?: false } // Don't understand why it is nullable here

    assertThat(outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get()).isEqualTo(0)
  }

  @Test
  fun `event is published to outbound topic received by queue with no dlq`() = runTest {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).messageAttributes(
        mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
      ).build(),
    )

    await untilCallTo { outboundTestNoDlqSqsClient.countMessagesOnQueue(outboundTestNoDlqQueueUrl).get() } matches { it == 1 }

    val (message) = ReceiveMessageRequest.builder().queueUrl(outboundTestNoDlqQueueUrl).build()
      .let { outboundTestNoDlqSqsClient.receiveMessage(it).get().messages()[0].body() }
      .let { objectMapper.readValue(it, Message::class.java) }
    val receivedEvent = objectMapper.readValue(message, HmppsEvent::class.java)

    assertThat(receivedEvent.id).isEqualTo("event-id")
    assertThat(receivedEvent.type).isEqualTo("offender.movement.reception")
    assertThat(receivedEvent.contents).isEqualTo("some event contents")
  }

  @Test
  fun `event is moved to the dead letter queue when an exception is thrown`() = runTest {
    doThrow(RuntimeException("some error")).whenever(inboundMessageServiceSpy).handleMessage(any())

    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder()
        .topicArn(inboundTopicArn)
        .message(gsonString(event))
        .messageAttributes(
          mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
        )
        .build(),
    )

    await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }
    assertThat(inboundSqsClient.countAllMessagesOnQueue(inboundQueueUrl).get()).isEqualTo(0)
  }

  @Test
  fun `event published to fifo topic is received by fifo queue`() = runTest {
    val event = HmppsEvent("fifo-event-id", "FIFO-EVENT", "some FIFO contents")
    fifoSnsClient.publish(
      PublishRequest.builder().topicArn(fifoTopicArn).message(gsonString(event))
        .messageGroupId(UUID.randomUUID().toString())
        .messageAttributes(
          mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
        ).build(),
    )

    await untilCallTo { fifoSqsClient.countMessagesOnQueue(fifoQueueUrl).get() } matches { it == 1 }

    val (message) = ReceiveMessageRequest.builder().queueUrl(fifoQueueUrl).build()
      .let { fifoSqsClient.receiveMessage(it).get().messages()[0].body() }
      .let { objectMapper.readValue(it, Message::class.java) }
    val receivedEvent = objectMapper.readValue(message, HmppsEvent::class.java)

    assertThat(receivedEvent.id).isEqualTo("fifo-event-id")
    assertThat(receivedEvent.type).isEqualTo("FIFO-EVENT")
    assertThat(receivedEvent.contents).isEqualTo("some FIFO contents")
  }
}
