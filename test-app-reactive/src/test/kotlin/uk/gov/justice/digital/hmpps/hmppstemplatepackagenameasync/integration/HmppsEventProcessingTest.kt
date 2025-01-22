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
import uk.gov.justice.hmpps.sqs.publish
import uk.gov.justice.hmpps.sqs.publishLargeMessage
import java.io.File
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
    fifoTopic.publish(
      eventType = event.type,
      event = gsonString(event),
      messageGroupId = UUID.randomUUID().toString(),
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

  @Test
  fun `large message published to fifo topic is received by fifo queue`() = runTest {
    val file = File("src/test/resources/events/large-sns-message.json")
    val event = HmppsEvent("fifo-event-id", "FIFO-EVENT", file.toString())

    val amazonS3AsyncClient = s3Client
    fifoTopic.publishLargeMessage(
      eventType = event.type,
      event = gsonString(event),
      messageGroupId = UUID.randomUUID().toString(),
      amazonS3AsyncClient = s3Client,
      s3BucketName = bucketName,
    )

    assertThat(amazonS3AsyncClient.listBuckets().get().buckets().size).isEqualTo(1)

    await untilCallTo { fifoSqsClient.countMessagesOnQueue(fifoQueueUrl).get() } matches { it == 1 }

    val snsMessage = ReceiveMessageRequest.builder().queueUrl(fifoQueueUrl).build()
      .let { fifoSqsClient.receiveMessage(it).get().messages()[0].body() }

    val mappedMessage = objectMapper.readValue(snsMessage, Message::class.java)

    assertThat(mappedMessage.MessageAttributes.get("eventType")?.Value).isEqualTo("FIFO-EVENT")

    val s3Message = snsMessage.let { objectMapper.readValue(it, LinkedHashMap::class.java).get("Message") }
      .let { objectMapper.readValue(it.toString(), ArrayList::class.java) }
      .let { it[1] as LinkedHashMap<*, *> }

    assertThat(s3Message.keys).contains("s3Key", "s3BucketName")
  }
}
