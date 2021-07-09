package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.DeleteMessageRequest
import com.amazonaws.services.sqs.model.GetQueueAttributesResult
import com.amazonaws.services.sqs.model.GetQueueUrlResult
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.amazonaws.services.sqs.model.ReceiveMessageResult
import com.microsoft.applicationinsights.TelemetryClient
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.check
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.isNull
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyNoMoreInteractions
import com.nhaarman.mockitokotlin2.whenever
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple.tuple
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import com.amazonaws.services.sqs.model.PurgeQueueRequest as AwsPurgeQueueRequest

class HmppsQueueServiceTest {

  private val telemetryClient = mock<TelemetryClient>()
  private val hmppsTopicFactory = mock<HmppsTopicFactory>()
  private val hmppsQueueFactory = mock<HmppsQueueFactory>()
  private val hmppsSqsProperties = mock<HmppsSqsProperties>()
  private lateinit var hmppsQueueService: HmppsQueueService

  @Nested
  inner class HmppsQueues {

    private val sqsClient = mock<AmazonSQS>()
    private val sqsDlqClient = mock<AmazonSQS>()

    @BeforeEach
    fun `add test data`() {
      whenever(sqsClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))
      whenever(sqsDlqClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some dlq url"))
      whenever(hmppsQueueFactory.createHmppsQueues(any(), any()))
        .thenReturn(
          listOf(
            HmppsQueue("some queue id", sqsClient, "some queue name", sqsDlqClient, "some dlq name"),
            HmppsQueue("another queue id", mock(), "another queue name", mock(), "another dlq name"),
          )
        )

      hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicFactory, hmppsQueueFactory, hmppsSqsProperties)
    }

    @Test
    fun `finds an hmpps queue by queue id`() {
      assertThat(hmppsQueueService.findByQueueId("some queue id")?.queueUrl).isEqualTo("some queue url")
    }

    @Test
    fun `finds an hmpps queue by queue name`() {
      assertThat(hmppsQueueService.findByQueueName("some queue name")?.queueUrl).isEqualTo("some queue url")
    }

    @Test
    fun `finds an hmpps queue by dlq name`() {
      assertThat(hmppsQueueService.findByDlqName("some dlq name")?.dlqUrl).isEqualTo("some dlq url")
    }

    @Test
    fun `returns null if queue id not found`() {
      assertThat(hmppsQueueService.findByQueueId("unknown")).isNull()
    }

    @Test
    fun `returns null if queue not found`() {
      assertThat(hmppsQueueService.findByQueueName("unknown")).isNull()
    }

    @Test
    fun `returns null if dlq not found`() {
      assertThat(hmppsQueueService.findByDlqName("unknown")).isNull()
    }
  }

  @Nested
  inner class RetryDlqMessages {

    private val dlqSqs = mock<AmazonSQS>()
    private val queueSqs = mock<AmazonSQS>()

    @BeforeEach
    fun `stub getting of queue url`() {
      whenever(queueSqs.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("queueUrl"))
      whenever(dlqSqs.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("dlqUrl"))

      hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicFactory, hmppsQueueFactory, hmppsSqsProperties)
    }

    @Nested
    inner class NoMessages {
      @BeforeEach
      fun `finds zero messages on dlq`() {
        whenever(dlqSqs.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages")))).thenReturn(
          GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "0"))
        )
      }

      @Test
      fun `should not attempt any transfer`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).getQueueAttributes("dlqUrl", listOf("ApproximateNumberOfMessages"))
        verify(dlqSqs, times(0)).receiveMessage(any<ReceiveMessageRequest>())
      }

      @Test
      fun `should return empty result`() {
        val result =
          hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        assertThat(result.messagesFoundCount).isEqualTo(0)
        assertThat(result.messages).isEmpty()
      }

      @Test
      fun `should not create telemetry event`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verifyNoMoreInteractions(telemetryClient)
      }
    }

    @Nested
    inner class SingleMessage {
      @BeforeEach
      fun `finds a single message on the dlq`() {
        whenever(dlqSqs.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages")))).thenReturn(
          GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "1"))
        )
        whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>()))
          .thenReturn(
            ReceiveMessageResult().withMessages(
              Message().withBody("message-body").withReceiptHandle("message-receipt-handle")
            )
          )

        hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicFactory, hmppsQueueFactory, hmppsSqsProperties)
      }

      @Test
      fun `should receive message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).receiveMessage(
          check<ReceiveMessageRequest> {
            assertThat(it.queueUrl).isEqualTo("dlqUrl")
            assertThat(it.maxNumberOfMessages).isEqualTo(1)
          }
        )
      }

      @Test
      fun `should delete message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).deleteMessage(
          check {
            assertThat(it.queueUrl).isEqualTo("dlqUrl")
            assertThat(it.receiptHandle).isEqualTo("message-receipt-handle")
          }
        )
      }

      @Test
      fun `should send message to the main queue`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(queueSqs).sendMessage("queueUrl", "message-body")
      }

      @Test
      fun `should return the message`() {
        val result = hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        assertThat(result.messagesFoundCount).isEqualTo(1)
        assertThat(result.messages)
          .extracting(Message::getBody, Message::getReceiptHandle)
          .containsExactly(tuple("message-body", "message-receipt-handle"))
      }

      @Test
      fun `should create telemetry event`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(telemetryClient).trackEvent(
          eq("RetryDLQ"),
          check {
            assertThat(it).containsEntry("dlq-name", "some dlq name")
            assertThat(it).containsEntry("messages-found", "1")
            assertThat(it).containsEntry("messages-retried", "1")
          },
          isNull()
        )
      }
    }

    @Nested
    inner class MultipleMessages {
      @BeforeEach
      fun `finds two message on the dlq`() {
        whenever(dlqSqs.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages")))).thenReturn(
          GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "2"))
        )
        whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>()))
          .thenReturn(
            ReceiveMessageResult().withMessages(
              Message().withBody("message-1-body").withReceiptHandle("message-1-receipt-handle")
            )
          )
          .thenReturn(
            ReceiveMessageResult().withMessages(
              Message().withBody("message-2-body").withReceiptHandle("message-2-receipt-handle")
            )
          )

        hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicFactory, hmppsQueueFactory, hmppsSqsProperties)
      }

      @Test
      fun `should receive message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs, times(2)).receiveMessage(
          check<ReceiveMessageRequest> {
            assertThat(it.queueUrl).isEqualTo("dlqUrl")
            assertThat(it.maxNumberOfMessages).isEqualTo(1)
          }
        )
      }

      @Test
      fun `should delete message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        val captor = argumentCaptor<DeleteMessageRequest>()
        verify(dlqSqs, times(2)).deleteMessage(captor.capture())

        assertThat(captor.firstValue.receiptHandle).isEqualTo("message-1-receipt-handle")
        assertThat(captor.secondValue.receiptHandle).isEqualTo("message-2-receipt-handle")
      }

      @Test
      fun `should send message to the main queue`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(queueSqs).sendMessage("queueUrl", "message-1-body")
        verify(queueSqs).sendMessage("queueUrl", "message-2-body")
      }

      @Test
      fun `should return the message`() {
        val result = hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        assertThat(result.messagesFoundCount).isEqualTo(2)
        assertThat(result.messages)
          .extracting(Message::getBody, Message::getReceiptHandle)
          .containsExactly(tuple("message-1-body", "message-1-receipt-handle"), tuple("message-2-body", "message-2-receipt-handle"))
      }
    }

    @Nested
    inner class MultipleMessagesSomeNotFound {
      @BeforeEach
      fun `finds only one of two message on the dlq`() {
        whenever(dlqSqs.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages")))).thenReturn(
          GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "2"))
        )
        whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>()))
          .thenReturn(
            ReceiveMessageResult().withMessages(
              Message().withBody("message-1-body").withReceiptHandle("message-1-receipt-handle")
            )
          )
          .thenReturn(ReceiveMessageResult())

        hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicFactory, hmppsQueueFactory, hmppsSqsProperties)
      }

      @Test
      fun `should receive message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs, times(2)).receiveMessage(
          check<ReceiveMessageRequest> {
            assertThat(it.queueUrl).isEqualTo("dlqUrl")
            assertThat(it.maxNumberOfMessages).isEqualTo(1)
          }
        )
      }

      @Test
      fun `should delete message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).deleteMessage(
          check {
            assertThat(it.queueUrl).isEqualTo("dlqUrl")
            assertThat(it.receiptHandle).isEqualTo("message-1-receipt-handle")
          }
        )
      }

      @Test
      fun `should send message to the main queue`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(queueSqs).sendMessage("queueUrl", "message-1-body")
      }

      @Test
      fun `should return the message`() {
        val result = hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        assertThat(result.messagesFoundCount).isEqualTo(2)
        assertThat(result.messages)
          .extracting(Message::getBody, Message::getReceiptHandle)
          .containsExactly(tuple("message-1-body", "message-1-receipt-handle"))
      }

      @Test
      fun `should create telemetry event`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(telemetryClient).trackEvent(
          eq("RetryDLQ"),
          check {
            assertThat(it).containsEntry("dlq-name", "some dlq name")
            assertThat(it).containsEntry("messages-found", "2")
            assertThat(it).containsEntry("messages-retried", "1")
          },
          isNull()
        )
      }
    }
  }

  @Nested
  inner class FindQueueToPurge {

    private val sqsClient = mock<AmazonSQS>()
    private val sqsDlqClient = mock<AmazonSQS>()

    @BeforeEach
    fun `add test data`() {
      whenever(sqsClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))
      whenever(sqsDlqClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some dlq url"))
      whenever(hmppsQueueFactory.createHmppsQueues(any(), any()))
        .thenReturn(
          listOf(
            HmppsQueue("some queue id", sqsClient, "some queue name", sqsDlqClient, "some dlq name"),
            HmppsQueue("another queue id", mock(), "another queue name", mock(), "another dlq name"),
          )
        )

      hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicFactory, hmppsQueueFactory, hmppsSqsProperties)
    }

    @Test
    fun `should find the main queue`() {
      val request = hmppsQueueService.findQueueToPurge("some queue name")

      assertThat(request?.queueName).isEqualTo("some queue name")
    }

    @Test
    fun `should find the dlq`() {
      val request = hmppsQueueService.findQueueToPurge("some dlq name")

      assertThat(request?.queueName).isEqualTo("some dlq name")
    }

    @Test
    fun `should return null if not queue or dlq`() {
      val request = hmppsQueueService.findQueueToPurge("unknown queue name")

      assertThat(request).isNull()
    }
  }

  @Nested
  inner class PurgeQueue {

    private val sqsClient = mock<AmazonSQS>()
    private val hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicFactory, hmppsQueueFactory, hmppsSqsProperties)

    @Test
    fun `no messages found, should not attempt to purge queue`() {
      stubMessagesOnQueue(0)

      hmppsQueueService.purgeQueue(PurgeQueueRequest("some queue", sqsClient, "some queue url"))

      verify(sqsClient, times(0)).purgeQueue(any())
    }

    @Test
    fun `no messages found, should not create telemetry event`() {
      stubMessagesOnQueue(0)

      hmppsQueueService.purgeQueue(PurgeQueueRequest("some queue", sqsClient, "some queue url"))

      verifyNoMoreInteractions(telemetryClient)
    }

    @Test
    fun `messages found, should attempt to purge queue`() {
      stubMessagesOnQueue(1)

      hmppsQueueService.purgeQueue(PurgeQueueRequest("some queue", sqsClient, "some queue url"))

      verify(sqsClient).purgeQueue(AwsPurgeQueueRequest("some queue url"))
    }

    @Test
    fun `messages found, should create telemetry event`() {
      stubMessagesOnQueue(1)

      hmppsQueueService.purgeQueue(PurgeQueueRequest("some queue", sqsClient, "some queue url"))

      verify(telemetryClient).trackEvent(
        eq("PurgeQueue"),
        check {
          assertThat(it).containsEntry("queue-name", "some queue")
          assertThat(it).containsEntry("messages-found", "1")
        },
        isNull()
      )
    }

    @Test
    fun `should return number of messages found to purge`() {
      stubMessagesOnQueue(5)

      val result = hmppsQueueService.purgeQueue(PurgeQueueRequest("some queue", sqsClient, "some queue url"))

      assertThat(result.messagesFoundCount).isEqualTo(5)
    }

    private fun stubMessagesOnQueue(messageCount: Int) {
      whenever(sqsClient.getQueueUrl(anyString()))
        .thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))
      whenever(sqsClient.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages"))))
        .thenReturn(GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "$messageCount")))
    }
  }
}
