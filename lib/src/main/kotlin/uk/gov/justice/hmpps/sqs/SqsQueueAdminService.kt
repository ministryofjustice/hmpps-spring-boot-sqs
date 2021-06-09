package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.DeleteMessageRequest
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest

class SqsQueueAdminService {

  fun retryDlqMessages(request: RetryDlqRequest): RetryDlqResult =
    with(request.hmppsQueue) {
      val messageCount = sqsAwsDlqClient.countMessagesOnQueue(dlqUrl)
      val messages = mutableListOf<Message>()
      repeat(messageCount) {
        sqsAwsDlqClient.receiveMessage(ReceiveMessageRequest(dlqUrl).withMaxNumberOfMessages(1)).messages.firstOrNull()
          ?.let { msg ->
            sqsAwsClient.sendMessage(queueUrl, msg.body)
            sqsAwsDlqClient.deleteMessage(DeleteMessageRequest(dlqUrl, msg.receiptHandle))
            messages += msg
          }
      }
      return RetryDlqResult(messageCount, messages.toList())
    }
}

data class RetryDlqRequest(val hmppsQueue: HmppsQueue)
data class RetryDlqResult(val messagesFoundCount: Int, val messages: List<Message>)

fun AmazonSQS.countMessagesOnQueue(queueUrl: String): Int =
  this.getQueueAttributes(queueUrl, listOf("ApproximateNumberOfMessages"))
    .let { it.attributes["ApproximateNumberOfMessages"]?.toInt() ?: 0 }
