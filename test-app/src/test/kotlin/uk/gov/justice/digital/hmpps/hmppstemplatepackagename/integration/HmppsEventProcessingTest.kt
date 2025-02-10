package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.retry.backoff.NoBackOffPolicy
import org.springframework.retry.policy.NeverRetryPolicy
import org.springframework.retry.policy.SimpleRetryPolicy
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.Message
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties
import uk.gov.justice.hmpps.sqs.HmppsTopicFactory
import uk.gov.justice.hmpps.sqs.countAllMessagesOnQueue
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import uk.gov.justice.hmpps.sqs.publish
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@TestConfiguration
class SnsConfig(private val hmppsTopicFactory: HmppsTopicFactory) {
  @Bean("outboundtopic-sns-client")
  fun topicSnsClient(
    hmppsSqsProperties: HmppsSqsProperties,
  ): SnsAsyncClient = hmppsTopicFactory.createSnsAsyncClient(topicId = "outboundtopic", topicConfig = HmppsSqsProperties.TopicConfig(arn = hmppsSqsProperties.topics["outboundtopic"]!!.arn), hmppsSqsProperties = hmppsSqsProperties)
}

@Import(SnsConfig::class)
class HmppsEventProcessingTest : IntegrationTestBase() {
  @SpyBean
  @Qualifier("outboundtopic-sns-client")
  protected lateinit var outboundSnsClientSpy: SnsAsyncClient

  @Nested
  inner class PublishRetry {
    @Test
    fun `events are published straight away when there are no errors`() {
      val response = outboundTopic.publish(
        eventType = "offender.movement.reception",
        event = gsonString(HmppsEvent("event-id", "offender.movement.reception", "some event contents")),
      )

      assertThat(response.messageId()).isNotNull()

      await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

      val (message) = objectMapper.readValue(outboundTestSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()).get().messages()[0].body(), Message::class.java)
      val receivedEvent = objectMapper.readValue(message, HmppsEvent::class.java)

      assertThat(receivedEvent.id).isEqualTo("event-id")
      assertThat(receivedEvent.type).isEqualTo("offender.movement.reception")
      assertThat(receivedEvent.contents).isEqualTo("some event contents")
    }

    @Test
    fun `by default events are published even if there is an initial error`() {
      doThrow(RuntimeException("some error"))
        .doCallRealMethod()
        .`when`(outboundSnsClientSpy).publish(any<PublishRequest>())

      val response = outboundTopic.publish(
        eventType = "offender.movement.reception",
        event = gsonString(HmppsEvent("event-id", "offender.movement.reception", "some event contents")),
      )

      assertThat(response.messageId()).isNotNull()

      verify(outboundSnsClientSpy, times(2)).publish(any<PublishRequest>())

      await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

      val (message) = objectMapper.readValue(outboundTestSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()).get().messages()[0].body(), Message::class.java)
      val receivedEvent = objectMapper.readValue(message, HmppsEvent::class.java)

      assertThat(receivedEvent.id).isEqualTo("event-id")
      assertThat(receivedEvent.type).isEqualTo("offender.movement.reception")
      assertThat(receivedEvent.contents).isEqualTo("some event contents")
    }

    @Test
    fun `by default events are published even if there is an initial error with the asynchronous response`() {
      doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doCallRealMethod()
        .`when`(outboundSnsClientSpy).publish(any<PublishRequest>())

      outboundTopic.publish(
        eventType = "offender.movement.reception",
        event = gsonString(HmppsEvent("event-id", "offender.movement.reception", "some event contents")),
      )

      verify(outboundSnsClientSpy, times(2)).publish(any<PublishRequest>())

      await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }
    }

    @Test
    fun `by default events are published eventually with a backoff  even if there is an error happens 3 times`() {
      doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doCallRealMethod()
        .`when`(outboundSnsClientSpy).publish(any<PublishRequest>())

      val duration = measureTime {
        outboundTopic.publish(
          eventType = "offender.movement.reception",
          event = gsonString(HmppsEvent("event-id", "offender.movement.reception", "some event contents")),
        )
      }

      verify(outboundSnsClientSpy, times(4)).publish(any<PublishRequest>())
      assertThat(duration).isGreaterThan(3.seconds)

      await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }
    }

    @Test
    fun `by default events are not published if there is an error that happens more than 3 times`() {
      doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doCallRealMethod()
        .`when`(outboundSnsClientSpy).publish(any<PublishRequest>())

      val exception = assertThrows<ExecutionException> {
        outboundTopic.publish(
          eventType = "offender.movement.reception",
          event = gsonString(HmppsEvent("event-id", "offender.movement.reception", "some event contents")),
          backOffPolicy = NoBackOffPolicy(),
        )
      }

      assertThat(exception.cause).isInstanceOf(RuntimeException::class.java)
      assertThat(exception.cause?.message).isEqualTo("some error")
    }

    @Test
    fun `can supply a different backoff and retry policy`() {
      doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doCallRealMethod()
        .`when`(outboundSnsClientSpy).publish(any<PublishRequest>())

      val duration = measureTime {
        outboundTopic.publish(
          eventType = "offender.movement.reception",
          event = gsonString(HmppsEvent("event-id", "offender.movement.reception", "some event contents")),
          retryPolicy = SimpleRetryPolicy().apply { maxAttempts = 5 },
          backOffPolicy = NoBackOffPolicy(),
        )
      }

      verify(outboundSnsClientSpy, times(5)).publish(any<PublishRequest>())
      assertThat(duration).isLessThan(5.seconds)

      await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }
    }

    @Test
    fun `can choose a never retry policy`() {
      doReturn(CompletableFuture.failedFuture<PublishResponse>(RuntimeException("some error")))
        .doCallRealMethod()
        .`when`(outboundSnsClientSpy).publish(any<PublishRequest>())

      val exception = assertThrows<ExecutionException> {
        outboundTopic.publish(
          eventType = "offender.movement.reception",
          event = gsonString(HmppsEvent("event-id", "offender.movement.reception", "some event contents")),
          retryPolicy = NeverRetryPolicy(),
        )
      }

      assertThat(exception.cause).isInstanceOf(RuntimeException::class.java)
      verify(outboundSnsClientSpy, times(1)).publish(any<PublishRequest>())
    }

    @Test
    fun `message attributes will contain eventType`() {
      outboundTopic.publish(
        eventType = "offender.movement.reception",
        event = gsonString(HmppsEvent("event-id", "offender.movement.reception", "some event contents")),
      )

      await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

      val sqsMessage = objectMapper.readValue(outboundTestSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()).get().messages()[0].body(), Message::class.java)
      assertThat(sqsMessage.MessageAttributes["eventType"]?.Value).isEqualTo("offender.movement.reception")
    }

    @Test
    fun `can overwrite attributes`() {
      outboundTopic.publish(
        eventType = "offender.movement.reception",
        event = gsonString(HmppsEvent("event-id", "offender.movement.reception", "some event contents")),
        attributes = mapOf(
          "fruit" to MessageAttributeValue.builder().dataType("String").stringValue("banana").build(),
          "eventType" to MessageAttributeValue.builder().dataType("String").stringValue("offender.movement.reception").build(),
        ),
      )

      await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

      val sqsMessage = objectMapper.readValue(outboundTestSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()).get().messages()[0].body(), Message::class.java)
      assertThat(sqsMessage.MessageAttributes["eventType"]?.Value).isEqualTo("offender.movement.reception")
      assertThat(sqsMessage.MessageAttributes["fruit"]?.Value).isEqualTo("banana")
    }
  }

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
  fun `two different events published to fifo topic are received by fifo queue`() = runTest {
    val event1 = HmppsEvent("fifo-event-id", "FIFO-EVENT", "some FIFO contents 1")
    val event2 = HmppsEvent("fifo-event-id", "FIFO-EVENT", "some FIFO contents 2")

    fifoTopic.publish(
      eventType = event1.type,
      event = gsonString(event1),
      messageGroupId = UUID.randomUUID().toString(),
    )
    await untilCallTo { fifoSqsClient.countMessagesOnQueue(fifoQueueUrl).get() } matches { it == 1 }

    fifoTopic.publish(
      eventType = event2.type,
      event = gsonString(event2),
      messageGroupId = UUID.randomUUID().toString(),
    )

    await untilCallTo { fifoSqsClient.countMessagesOnQueue(fifoQueueUrl).get() } matches { it == 2 }

    val (message) = ReceiveMessageRequest.builder().queueUrl(fifoQueueUrl).build()
      .let { fifoSqsClient.receiveMessage(it).get().messages()[0].body() }
      .let { objectMapper.readValue(it, Message::class.java) }
    val receivedEvent = objectMapper.readValue(message, HmppsEvent::class.java)

    assertThat(receivedEvent.id).isEqualTo("fifo-event-id")
    assertThat(receivedEvent.type).isEqualTo("FIFO-EVENT")
    assertThat(receivedEvent.contents).isEqualTo("some FIFO contents 1")

    val (message2) = ReceiveMessageRequest.builder().queueUrl(fifoQueueUrl).build()
      .let { fifoSqsClient.receiveMessage(it).get().messages()[0].body() }
      .let { objectMapper.readValue(it, Message::class.java) }
    val receivedEvent2 = objectMapper.readValue(message2, HmppsEvent::class.java)

    assertThat(receivedEvent2.id).isEqualTo("fifo-event-id")
    assertThat(receivedEvent2.type).isEqualTo("FIFO-EVENT")
    assertThat(receivedEvent2.contents).isEqualTo("some FIFO contents 2")
  }

  @Test
  fun `duplicate events published to fifo topic and one event is received by fifo queue`() = runTest {
    val event = HmppsEvent("fifo-event-id", "FIFO-EVENT", "some FIFO contents")

    fifoTopic.publish(
      eventType = event.type,
      event = gsonString(event),
      messageGroupId = UUID.randomUUID().toString(),
    )
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

    await untilCallTo { fifoSqsClient.countMessagesOnQueue(fifoQueueUrl).get() } matches { it == 0 }
  }
}
