package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.retry.backoff.NoBackOffPolicy
import org.springframework.retry.policy.NeverRetryPolicy
import org.springframework.retry.policy.SimpleRetryPolicy
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.Message
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.MessageAttributes
import uk.gov.justice.hmpps.sqs.HmppsQueueFactory
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import uk.gov.justice.hmpps.sqs.sendMessage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@TestConfiguration
class SqsConfig(private val hmppsQueueFactory: HmppsQueueFactory) {
  @Bean("outboundsqsonlytestqueue-sqs-client")
  fun queueSqsClient(
    hmppsSqsProperties: HmppsSqsProperties,
  ): SqsAsyncClient = hmppsQueueFactory.createSqsAsyncClient(
    queueConfig = HmppsSqsProperties.QueueConfig(
      queueName = hmppsSqsProperties.queues["outboundsqsonlytestqueue"]!!.queueName,
    ),
    hmppsSqsProperties = hmppsSqsProperties,
    sqsDlqClient = null,
  )
}

@Import(SqsConfig::class)
class HmppsQueueEventProcessingTest : IntegrationTestBase() {
  @MockitoSpyBean
  @Qualifier("outboundsqsonlytestqueue-sqs-client")
  protected lateinit var outboundSqsOnlyTestClientSpy: SqsAsyncClient

  @Nested
  inner class SendMessageRetry {
    val hmppsEvent = HmppsEvent("event-id", "type", "some event contents")

    val messageToSend = Message(
      Message = gsonString(hmppsEvent),
      MessageId = "123456",
      MessageAttributes = MessageAttributes(),
    )

    @Test
    fun `events are sent straight away when there are no errors`() {
      val response = outboundSqsOnlyTestQueue.sendMessage(
        eventType = "offender.movement.reception",
        event = gsonString(hmppsEvent),
      )

      assertThat(response.messageId()).isNotNull()

      await untilCallTo { outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get() } matches { it == 1 }

      val message = objectMapper.readValue(
        outboundSqsOnlyTestSqsClient.receiveMessage(
          ReceiveMessageRequest.builder().queueUrl(outboundSqsOnlyTestQueueUrl).build(),
        ).get().messages()[0].body(),
        HmppsEvent::class.java,
      )
      assertThat(message.id).isEqualTo("event-id")
      assertThat(message.type).isEqualTo("type")
      assertThat(message.contents).isEqualTo("some event contents")
    }

    @Test
    fun `by default events are sent even if there is an initial error`() {
      doThrow(RuntimeException("some error"))
        .doCallRealMethod()
        .`when`(outboundSqsOnlyTestClientSpy).sendMessage(any<SendMessageRequest>())

      val response = outboundSqsOnlyTestQueue.sendMessage(
        eventType = "offender.movement.reception",
        event = gsonString(hmppsEvent),
      )

      assertThat(response.messageId()).isNotNull()

      verify(outboundSqsOnlyTestClientSpy, times(2)).sendMessage(any<SendMessageRequest>())

      await untilCallTo { outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get() } matches { it == 1 }

      val message = objectMapper.readValue(
        outboundSqsOnlyTestSqsClient.receiveMessage(
          ReceiveMessageRequest.builder().queueUrl(
            outboundSqsOnlyTestQueueUrl,
          ).build(),
        ).get().messages()[0].body(),
        HmppsEvent::class.java,
      )

      assertThat(message.id).isEqualTo("event-id")
      assertThat(message.type).isEqualTo("type")
      assertThat(message.contents).isEqualTo("some event contents")
    }

    @Test
    fun `by default events are sent even if there is an initial error with the asynchronous response`() {
      doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doCallRealMethod()
        .`when`(outboundSqsOnlyTestClientSpy).sendMessage(any<SendMessageRequest>())

      outboundSqsOnlyTestQueue.sendMessage(
        eventType = "offender.movement.reception",
        event = gsonString(hmppsEvent),
      )

      verify(outboundSqsOnlyTestClientSpy, times(2)).sendMessage(any<SendMessageRequest>())

      await untilCallTo { outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get() } matches { it == 1 }
    }

    @Test
    fun `by default events are sent eventually with a backoff  even if there is an error happens 3 times`() {
      doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doCallRealMethod()
        .`when`(outboundSqsOnlyTestClientSpy).sendMessage(any<SendMessageRequest>())

      val duration = measureTime {
        outboundSqsOnlyTestQueue.sendMessage(
          eventType = "offender.movement.reception",
          event = gsonString(hmppsEvent),
        )
      }

      verify(outboundSqsOnlyTestClientSpy, times(4)).sendMessage(any<SendMessageRequest>())
      assertThat(duration).isGreaterThan(3.seconds)

      await untilCallTo { outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get() } matches { it == 1 }
    }

    @Test
    fun `by default events are not sent if there is an error that happens more than 3 times`() {
      doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doCallRealMethod()
        .`when`(outboundSqsOnlyTestClientSpy).sendMessage(any<SendMessageRequest>())

      val exception = assertThrows<ExecutionException> {
        outboundSqsOnlyTestQueue.sendMessage(
          eventType = "offender.movement.reception",
          event = gsonString(hmppsEvent),
          backOffPolicy = NoBackOffPolicy(),
        )
      }

      assertThat(exception.cause).isInstanceOf(RuntimeException::class.java)
      assertThat(exception.cause?.message).isEqualTo("some error")
    }

    @Test
    fun `can supply a different backoff and retry policy`() {
      doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doCallRealMethod()
        .`when`(outboundSqsOnlyTestClientSpy).sendMessage(any<SendMessageRequest>())

      val duration = measureTime {
        outboundSqsOnlyTestQueue.sendMessage(
          eventType = "offender.movement.reception",
          event = gsonString(hmppsEvent),
          retryPolicy = SimpleRetryPolicy().apply { maxAttempts = 5 },
          backOffPolicy = NoBackOffPolicy(),
        )
      }

      verify(outboundSqsOnlyTestClientSpy, times(5)).sendMessage(any<SendMessageRequest>())
      assertThat(duration).isLessThan(5.seconds)

      await untilCallTo { outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get() } matches { it == 1 }
    }

    @Test
    fun `can choose a never retry policy`() {
      doReturn(CompletableFuture.failedFuture<SendMessageResponse>(RuntimeException("some error")))
        .doCallRealMethod()
        .`when`(outboundSqsOnlyTestClientSpy).sendMessage(any<SendMessageRequest>())

      val exception = assertThrows<ExecutionException> {
        outboundSqsOnlyTestQueue.sendMessage(
          eventType = "offender.movement.reception",
          event = gsonString(hmppsEvent),
          retryPolicy = NeverRetryPolicy(),
        )
      }

      assertThat(exception.cause).isInstanceOf(RuntimeException::class.java)
      verify(outboundSqsOnlyTestClientSpy, times(1)).sendMessage(any<SendMessageRequest>())
    }

    @Test
    fun `message attributes will contain eventType`() {
      outboundSqsOnlyTestQueue.sendMessage(
        eventType = "offender.movement.reception",
        event = gsonString(hmppsEvent),
      )

      await untilCallTo { outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get() } matches { it == 1 }

      val response = outboundSqsOnlyTestSqsClient.receiveMessage(
        ReceiveMessageRequest.builder()
          .queueUrl(outboundSqsOnlyTestQueueUrl)
          .messageAttributeNames("All")
          .build(),
      ).get()
      val message = response.messages()[0]
      assertThat(message.messageAttributes()["eventType"]?.stringValue()).isEqualTo("offender.movement.reception")
    }

    @Test
    fun `can overwrite attributes`() {
      outboundSqsOnlyTestQueue.sendMessage(
        eventType = "offender.movement.reception",
        event = gsonString(hmppsEvent),
        attributes = mapOf(
          "fruit" to MessageAttributeValue.builder().dataType("String").stringValue("banana").build(),
          "eventType" to MessageAttributeValue.builder().dataType("String").stringValue("offender.movement.reception.overwritten").build(),
        ),
      )

      await untilCallTo { outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get() } matches { it == 1 }

      val response = outboundSqsOnlyTestSqsClient.receiveMessage(
        ReceiveMessageRequest.builder()
          .queueUrl(outboundSqsOnlyTestQueueUrl)
          .messageAttributeNames("All")
          .build(),
      ).get()
      val message = response.messages()[0]
      assertThat(message.messageAttributes()["eventType"]?.stringValue()).isEqualTo("offender.movement.reception.overwritten")
      assertThat(message.messageAttributes()["fruit"]?.stringValue()).isEqualTo("banana")
    }
  }
}
