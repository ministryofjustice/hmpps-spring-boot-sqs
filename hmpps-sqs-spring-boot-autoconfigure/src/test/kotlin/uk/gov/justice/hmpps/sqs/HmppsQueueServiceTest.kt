package uk.gov.justice.hmpps.sqs

import com.microsoft.applicationinsights.TelemetryClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

class HmppsQueueServiceTest {

  private val telemetryClient = mock<TelemetryClient>()
  private val hmppsTopicService = mock<HmppsTopicService>()
  private val hmppsQueueFactory = mock<HmppsQueueFactory>()
  private val hmppsSqsProperties = mock<HmppsSqsProperties>()
  private lateinit var hmppsQueueService: HmppsQueueService

  @Nested
  inner class HmppsQueues {

    private val sqsClient = mock<SqsClient>()
    private val sqsDlqClient = mock<SqsClient>()

    @BeforeEach
    fun `add test data`() {
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some queue url").build())
      whenever(sqsDlqClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some dlq url").build())
      whenever(hmppsQueueFactory.createHmppsQueues(any(), any(), any()))
        .thenReturn(
          listOf(
            HmppsQueue("some queue id", sqsClient, "some queue name", sqsDlqClient, "some dlq name"),
            HmppsQueue("another queue id", mock(), "another queue name", mock(), "another dlq name"),
          )
        )

      hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicService, hmppsQueueFactory, hmppsSqsProperties)
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

    private val dlqSqs = mock<SqsClient>()
    private val queueSqs = mock<SqsClient>()

    @BeforeEach
    fun `stub getting of queue url`() {
      whenever(queueSqs.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("queueUrl").build())
      whenever(dlqSqs.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("dlqUrl").build())

      hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicService, hmppsQueueFactory, hmppsSqsProperties)
    }

    @Nested
    inner class NoMessages {
      @BeforeEach
      fun `finds zero messages on dlq`() {
        whenever(dlqSqs.getQueueAttributes(any<GetQueueAttributesRequest>())).thenReturn(
          GetQueueAttributesResponse.builder().attributes(mapOf(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES to "0")).build()
        )
      }

      @Test
      fun `should not attempt any transfer`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl("dlqUrl").attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES).build())
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
        whenever(dlqSqs.getQueueAttributes(any<GetQueueAttributesRequest>())).thenReturn(
          GetQueueAttributesResponse.builder().attributes(mapOf(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES to "1")).build()
        )
        whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>()))
          .thenReturn(
            ReceiveMessageResponse.builder().messages(
              Message.builder()
                .body(
                  """
                    {
                      "Message":{
                        "id":"event-id",
                        "contents":"event-contents",
                        "longProperty":12345678
                      },
                      "MessageId":"message-id-1"
                    }
                  """.trimIndent()
                )
                .receiptHandle("message-receipt-handle")
                .messageId("external-message-id-1")
                .messageAttributes(mutableMapOf("some" to stringAttributeOf("attribute")))
                .build()
            ).build()
          )

        hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicService, hmppsQueueFactory, hmppsSqsProperties)
      }

      @Test
      fun `should receive message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).receiveMessage(
          check<ReceiveMessageRequest> {
            assertThat(it.queueUrl()).isEqualTo("dlqUrl")
            assertThat(it.maxNumberOfMessages()).isEqualTo(1)
          }
        )
      }

      @Test
      fun `should delete message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).deleteMessage(
          check<DeleteMessageRequest> {
            assertThat(it.queueUrl()).isEqualTo("dlqUrl")
            assertThat(it.receiptHandle()).isEqualTo("message-receipt-handle")
          }
        )
      }

      @Test
      fun `should send message to the main queue`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))
        verify(queueSqs).sendMessage(
          check<SendMessageRequest> {
            assertThat(it.queueUrl()).isEqualTo("queueUrl")
            assertThat(it.messageBody()).isNotEmpty
            assertThat(it.messageAttributes()).containsEntry("some", stringAttributeOf("attribute"))
          }
        )
      }

      @Test
      fun `should return the message`() {
        val result = hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        assertThat(result.messagesFoundCount).isEqualTo(1)
        assertThat(result.messages[0].messageId).isEqualTo("external-message-id-1")
        assertThat(result.messages[0].body["Message"]).isNotNull
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
        whenever(dlqSqs.getQueueAttributes(any<GetQueueAttributesRequest>())).thenReturn(
          GetQueueAttributesResponse.builder().attributes(mapOf(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES to "2")).build()
        )
        whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>()))
          .thenReturn(
            ReceiveMessageResponse.builder().messages(
              Message.builder()
                .body(
                  """
                    {
                      "Message":{
                        "id":"event-id",
                        "contents":"event-contents",
                        "longProperty":12345678
                      },
                      "MessageId":"message-id-1"
                    }
                  """.trimIndent()
                )
                .receiptHandle("message-1-receipt-handle")
                .messageId("external-message-id-1")
                .messageAttributes(mutableMapOf("some" to stringAttributeOf("attribute-1")))
                .build()
            ).build()
          )
          .thenReturn(
            ReceiveMessageResponse.builder().messages(
              Message.builder()
                .body(
                  """
                    {
                      "Message":{
                        "id":"event-id",
                        "contents":"event-contents",
                        "longProperty":12345678
                      },
                      "MessageId":"message-id-2"
                    }
                  """.trimIndent()
                )
                .receiptHandle("message-2-receipt-handle")
                .messageId("external-message-id-2")
                .messageAttributes(mutableMapOf("some" to stringAttributeOf("attribute-2")))
                .build()
            ).build()
          )

        hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicService, hmppsQueueFactory, hmppsSqsProperties)
      }

      @Test
      fun `should receive message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs, times(2)).receiveMessage(
          check<ReceiveMessageRequest> {
            assertThat(it.queueUrl()).isEqualTo("dlqUrl")
            assertThat(it.maxNumberOfMessages()).isEqualTo(1)
          }
        )
      }

      @Test
      fun `should delete message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        val captor = argumentCaptor<DeleteMessageRequest>()
        verify(dlqSqs, times(2)).deleteMessage(captor.capture())

        assertThat(captor.firstValue.receiptHandle()).isEqualTo("message-1-receipt-handle")
        assertThat(captor.secondValue.receiptHandle()).isEqualTo("message-2-receipt-handle")
      }

      @Test
      fun `should send message to the main queue`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        val captor = argumentCaptor<SendMessageRequest>()
        verify(queueSqs, times(2)).sendMessage(captor.capture())

        assertThat(captor.firstValue.queueUrl()).isEqualTo("queueUrl")
        assertThat(captor.firstValue.messageBody()).contains("message-id-1")
        assertThat((captor.firstValue.messageAttributes()["some"] as MessageAttributeValue).stringValue()).isEqualTo("attribute-1")
        assertThat(captor.secondValue.queueUrl()).isEqualTo("queueUrl")
        assertThat(captor.secondValue.messageBody()).contains("message-id-2")
        assertThat((captor.secondValue.messageAttributes()["some"] as MessageAttributeValue).stringValue()).isEqualTo("attribute-2")
      }

      @Test
      fun `should return the message`() {
        val result = hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        assertThat(result.messagesFoundCount).isEqualTo(2)
        assertThat(result.messages[0].messageId).isEqualTo("external-message-id-1")
        assertThat(result.messages[0].body["Message"]).isNotNull
        assertThat(result.messages[1].messageId).isEqualTo("external-message-id-2")
        assertThat(result.messages[1].body["Message"]).isNotNull
      }
    }

    @Nested
    inner class MultipleMessagesSomeNotFound {
      @BeforeEach
      fun `finds only one of two message on the dlq`() {
        whenever(dlqSqs.getQueueAttributes(any<GetQueueAttributesRequest>())).thenReturn(
          GetQueueAttributesResponse.builder().attributes(mapOf(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES to "2")).build()
        )
        whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>())).thenReturn(
          ReceiveMessageResponse.builder().messages(
            Message.builder()
              .body(
                """
                    {
                      "Message":{
                        "id":"event-id",
                        "contents":"event-contents",
                        "longProperty":12345678
                      },
                      "MessageId":"message-id-1"
                    }
                """.trimIndent()
              )
              .receiptHandle("message-1-receipt-handle")
              .messageId("external-message-id-1")
              .messageAttributes(mutableMapOf("some" to stringAttributeOf("attribute-1")))
              .build()
          ).build()
        )
          .thenReturn(ReceiveMessageResponse.builder().build())

        hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicService, hmppsQueueFactory, hmppsSqsProperties)
      }

      @Test
      fun `should receive message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs, times(2)).receiveMessage(
          check<ReceiveMessageRequest> {
            assertThat(it.queueUrl()).isEqualTo("dlqUrl")
            assertThat(it.maxNumberOfMessages()).isEqualTo(1)
          }
        )
      }

      @Test
      fun `should delete message from the dlq`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).deleteMessage(
          check<DeleteMessageRequest> {
            assertThat(it.queueUrl()).isEqualTo("dlqUrl")
            assertThat(it.receiptHandle()).isEqualTo("message-1-receipt-handle")
          }
        )
      }

      @Test
      fun `should send message to the main queue`() {
        hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        val captor = argumentCaptor<SendMessageRequest>()
        verify(queueSqs).sendMessage(captor.capture())

        assertThat(captor.firstValue.queueUrl()).isEqualTo("queueUrl")
        assertThat(captor.firstValue.messageBody()).contains("message-id-1")
        assertThat((captor.firstValue.messageAttributes()["some"] as MessageAttributeValue).stringValue()).isEqualTo("attribute-1")
      }

      @Test
      fun `should return the message`() {
        val result = hmppsQueueService.retryDlqMessages(RetryDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name")))

        assertThat(result.messagesFoundCount).isEqualTo(2)
        assertThat(result.messages[0].messageId).isEqualTo("external-message-id-1")
        assertThat(result.messages[0].body["Message"]).isNotNull
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
  inner class GetDlqMessages {
    private val dlqSqs = mock<SqsClient>()
    private val queueSqs = mock<SqsClient>()

    @BeforeEach
    fun `stub getting of queue url`() {
      whenever(queueSqs.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("queueUrl").build())
      whenever(dlqSqs.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("dlqUrl").build())

      hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicService, hmppsQueueFactory, hmppsSqsProperties)
    }

    @BeforeEach
    fun `gets a message on the dlq`() {
      whenever(dlqSqs.getQueueAttributes(any<GetQueueAttributesRequest>())).thenReturn(
        GetQueueAttributesResponse.builder().attributes(mapOf(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES to "1")).build()
      )
      whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>()))
        .thenReturn(
          ReceiveMessageResponse.builder().messages(
            Message.builder().body(
              """{
                    "Message":{
                        "id":"event-id",
                        "contents":"event-contents",
                        "longProperty":12345678
                    },
                    "MessageId":"message-id-1"
                }"""
            )
              .receiptHandle("message-1-receipt-handle")
              .messageId("external-message-id-1")
              .build()
          ).build()
        )

      hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicService, hmppsQueueFactory, hmppsSqsProperties)
    }

    @Test
    fun `should get messages from the dlq`() {
      val dlqResult = hmppsQueueService.getDlqMessages(GetDlqRequest(HmppsQueue("some queue id", queueSqs, "some queue name", dlqSqs, "some dlq name"), 10))
      assertThat(dlqResult.messagesFoundCount).isEqualTo(1)
      assertThat(dlqResult.messagesReturnedCount).isEqualTo(1)
      assertThat(dlqResult.messages).hasSize(1)
      assertThat(dlqResult.messages[0].messageId).isEqualTo("external-message-id-1")
      val messageMap = dlqResult.messages[0].body["Message"] as Map<*, *>
      assertThat(messageMap["longProperty"]).isEqualTo(12345678L)
      verify(dlqSqs).receiveMessage(
        check<ReceiveMessageRequest> {
          assertThat(it.queueUrl()).isEqualTo("dlqUrl")
        }
      )
    }
  }

  @Nested
  inner class FindQueueToPurge {

    private val sqsClient = mock<SqsClient>()
    private val sqsDlqClient = mock<SqsClient>()

    @BeforeEach
    fun `add test data`() {
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some queue url").build())
      whenever(sqsDlqClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some dlq url").build())
      whenever(hmppsQueueFactory.createHmppsQueues(any(), any(), any()))
        .thenReturn(
          listOf(
            HmppsQueue("some queue id", sqsClient, "some queue name", sqsDlqClient, "some dlq name"),
            HmppsQueue("another queue id", mock(), "another queue name", mock(), "another dlq name"),
          )
        )

      hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicService, hmppsQueueFactory, hmppsSqsProperties)
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

    private val sqsClient = mock<SqsClient>()
    private val hmppsQueueService = HmppsQueueService(telemetryClient, hmppsTopicService, hmppsQueueFactory, hmppsSqsProperties)

    @Test
    fun `no messages found, should not attempt to purge queue`() {
      stubMessagesOnQueue(0)

      hmppsQueueService.purgeQueue(PurgeQueueRequest("some queue", sqsClient, "some queue url"))

      verify(sqsClient, times(0)).purgeQueue(any<PurgeQueueRequest>())
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

      verify(sqsClient).purgeQueue(PurgeQueueRequest.builder().queueUrl("some queue url").build())
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
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>()))
        .thenReturn(GetQueueUrlResponse.builder().queueUrl("some queue url").build())
      whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(GetQueueAttributesResponse.builder().attributes(mapOf(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES to "$messageCount")).build())
    }
  }
}

private fun stringAttributeOf(value: String?): MessageAttributeValue? {
  return MessageAttributeValue.builder()
    .dataType("String")
    .stringValue(value)
    .build()
}
