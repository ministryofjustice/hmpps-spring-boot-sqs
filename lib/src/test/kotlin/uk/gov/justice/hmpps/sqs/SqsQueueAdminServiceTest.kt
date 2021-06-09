package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.DeleteMessageRequest
import com.amazonaws.services.sqs.model.GetQueueAttributesResult
import com.amazonaws.services.sqs.model.GetQueueUrlResult
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.amazonaws.services.sqs.model.ReceiveMessageResult
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.check
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple.tuple
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString

internal class SqsQueueAdminServiceTest {

  private val queueAdminService = SqsQueueAdminService()

  @Nested
  inner class TransferAllMessages() {

    private val dlqSqs = mock<AmazonSQS>()
    private val queueSqs = mock<AmazonSQS>()

    @BeforeEach
    fun `stub getting of queue url`() {
      whenever(queueSqs.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("queueUrl"))
      whenever(dlqSqs.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("dlqUrl"))
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
      fun `No messages does not attempt any transfer`() {
        queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).getQueueAttributes("dlqUrl", listOf("ApproximateNumberOfMessages"))
        verify(dlqSqs, times(0)).receiveMessage(any<ReceiveMessageRequest>())
      }

      @Test
      fun `No messages returns empty result`() {
        val result =
          queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        assertThat(result.messagesFoundCount).isEqualTo(0)
        assertThat(result.messages).isEmpty()
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
      }

      @Test
      fun `Receives message from the dlq`() {
        queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).receiveMessage(
          check<ReceiveMessageRequest> {
            assertThat(it.queueUrl).isEqualTo("dlqUrl")
            assertThat(it.maxNumberOfMessages).isEqualTo(1)
          }
        )
      }

      @Test
      fun `Deletes message from the dlq`() {
        queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).deleteMessage(
          check {
            assertThat(it.queueUrl).isEqualTo("dlqUrl")
            assertThat(it.receiptHandle).isEqualTo("message-receipt-handle")
          }
        )
      }

      @Test
      fun `Sends message to the main queue`() {
        queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(queueSqs).sendMessage("queueUrl", "message-body")
      }

      @Test
      fun `Returns the message`() {
        val result = queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        assertThat(result.messagesFoundCount).isEqualTo(1)
        assertThat(result.messages)
          .extracting(Message::getBody, Message::getReceiptHandle)
          .containsExactly(tuple("message-body", "message-receipt-handle"))
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
      }

      @Test
      fun `Receives message from the dlq`() {
        queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs, times(2)).receiveMessage(
          check<ReceiveMessageRequest> {
            assertThat(it.queueUrl).isEqualTo("dlqUrl")
            assertThat(it.maxNumberOfMessages).isEqualTo(1)
          }
        )
      }

      @Test
      fun `Deletes message from the dlq`() {
        queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        val captor = argumentCaptor<DeleteMessageRequest>()
        verify(dlqSqs, times(2)).deleteMessage(captor.capture())

        assertThat(captor.firstValue.receiptHandle).isEqualTo("message-1-receipt-handle")
        assertThat(captor.secondValue.receiptHandle).isEqualTo("message-2-receipt-handle")
      }

      @Test
      fun `Sends message to the main queue`() {
        queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(queueSqs).sendMessage("queueUrl", "message-1-body")
        verify(queueSqs).sendMessage("queueUrl", "message-2-body")
      }

      @Test
      fun `Returns the message`() {
        val result = queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

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
      }

      @Test
      fun `Receives message from the dlq`() {
        queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs, times(2)).receiveMessage(
          check<ReceiveMessageRequest> {
            assertThat(it.queueUrl).isEqualTo("dlqUrl")
            assertThat(it.maxNumberOfMessages).isEqualTo(1)
          }
        )
      }

      @Test
      fun `Deletes message from the dlq`() {
        queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(dlqSqs).deleteMessage(
          check {
            assertThat(it.queueUrl).isEqualTo("dlqUrl")
            assertThat(it.receiptHandle).isEqualTo("message-1-receipt-handle")
          }
        )
      }

      @Test
      fun `Sends message to the main queue`() {
        queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        verify(queueSqs).sendMessage("queueUrl", "message-1-body")
      }

      @Test
      fun `Returns the message`() {
        val result = queueAdminService.retryDlqMessages(TransferMessagesRequest(HmppsQueue(queueSqs, "some queue name", dlqSqs, "some dlq name")))

        assertThat(result.messagesFoundCount).isEqualTo(2)
        assertThat(result.messages)
          .extracting(Message::getBody, Message::getReceiptHandle)
          .containsExactly(tuple("message-1-body", "message-1-receipt-handle"))
      }
    }
  }
}
