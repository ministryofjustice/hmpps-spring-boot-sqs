package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.DeleteMessageRequest
import com.amazonaws.services.sqs.model.GetQueueAttributesResult
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.amazonaws.services.sqs.model.ReceiveMessageResult
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.check
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyNoMoreInteractions
import com.nhaarman.mockitokotlin2.whenever
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple.tuple
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString

internal class QueueAdminServiceTest {

  private val queueAdminService = QueueAdminService()

  @Nested
  inner class TransferAllMessages() {

    private val dlqSqs = mock<AmazonSQS>()
    val queueSqs = mock<AmazonSQS>()

    @Nested
    inner class NoMessages {
      @Test
      fun `No messages does not attempt any transfer`() {
        whenever(dlqSqs.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages")))).thenReturn(
          GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "0"))
        )

        queueAdminService.transferAllMessages(TransferMessagesRequest(dlqSqs, "dlqUrl", queueSqs, "queueUrl"))

        verify(dlqSqs).getQueueAttributes("dlqUrl", listOf("ApproximateNumberOfMessages"))
        verifyNoMoreInteractions(dlqSqs)
        verifyNoMoreInteractions(queueSqs)
      }

      @Test
      fun `No messages returns empty result`() {
        whenever(dlqSqs.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages")))).thenReturn(
          GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "0"))
        )

        val result =
          queueAdminService.transferAllMessages(TransferMessagesRequest(dlqSqs, "dlqUrl", queueSqs, "queueUrl"))

        assertThat(result.messagesFoundCount).isEqualTo(0)
        assertThat(result.messages).isEmpty()
      }
    }

    @Nested
    inner class SingleMessage {
      @Test
      fun `Receives message from the dlq`() {
        whenever(dlqSqs.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages")))).thenReturn(
          GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "1"))
        )
        whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>()))
          .thenReturn(
            ReceiveMessageResult().withMessages(
              Message().withBody("message-body").withReceiptHandle("message-receipt-handle")
            )
          )

        queueAdminService.transferAllMessages(TransferMessagesRequest(dlqSqs, "dlqUrl", queueSqs, "queueUrl"))

        verify(dlqSqs).receiveMessage(check<ReceiveMessageRequest> {
          assertThat(it.queueUrl).isEqualTo("dlqUrl")
          assertThat(it.maxNumberOfMessages).isEqualTo(1)
        })
      }

      @Test
      fun `Deletes message from the dlq`() {
        whenever(dlqSqs.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages")))).thenReturn(
          GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "1"))
        )
        whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>()))
          .thenReturn(
            ReceiveMessageResult().withMessages(
              Message().withBody("message-body").withReceiptHandle("message-receipt-handle")
            )
          )

        queueAdminService.transferAllMessages(TransferMessagesRequest(dlqSqs, "dlqUrl", queueSqs, "queueUrl"))

        verify(dlqSqs).deleteMessage(check<DeleteMessageRequest> {
          assertThat(it.queueUrl).isEqualTo("dlqUrl")
          assertThat(it.receiptHandle).isEqualTo("message-receipt-handle")
        })
      }

      @Test
      fun `Sends message to the main queue`() {
        whenever(dlqSqs.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages")))).thenReturn(
          GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "1"))
        )
        whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>()))
          .thenReturn(
            ReceiveMessageResult().withMessages(
              Message().withBody("message-body").withReceiptHandle("message-receipt-handle")
            )
          )

        queueAdminService.transferAllMessages(TransferMessagesRequest(dlqSqs, "dlqUrl", queueSqs, "queueUrl"))

        verify(queueSqs).sendMessage("queueUrl", "message-body")
      }

      @Test
      fun `Returns the message`() {
        whenever(dlqSqs.getQueueAttributes(anyString(), eq(listOf("ApproximateNumberOfMessages")))).thenReturn(
          GetQueueAttributesResult().withAttributes(mapOf("ApproximateNumberOfMessages" to "1"))
        )
        whenever(dlqSqs.receiveMessage(any<ReceiveMessageRequest>()))
          .thenReturn(
            ReceiveMessageResult().withMessages(
              Message().withBody("message-body").withReceiptHandle("message-receipt-handle")
            )
          )

        val result = queueAdminService.transferAllMessages(TransferMessagesRequest(dlqSqs, "dlqUrl", queueSqs, "queueUrl"))

        assertThat(result.messagesFoundCount).isEqualTo(1)
        assertThat(result.messages)
          .extracting(Message::getBody, Message::getReceiptHandle)
          .containsExactly(tuple("message-body", "message-receipt-handle"))
      }
    }
  }
}