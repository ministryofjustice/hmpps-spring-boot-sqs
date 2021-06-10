package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.DeleteMessageRequest
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class HmppsQueueService {

  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }

  private val hmppsQueues: MutableList<HmppsQueue> = mutableListOf()

  fun registerHmppsQueue(hmppsQueue: HmppsQueue) {
    hmppsQueues += hmppsQueue
  }

  fun findByQueueName(queueName: String) = hmppsQueues.associateBy { it.queueName }.getOrDefault(queueName, null)
  fun findByDlqName(dlqName: String) = hmppsQueues.associateBy { it.dlqName }.getOrDefault(dlqName, null)

  fun retryDlqMessages(request: RetryDlqRequest): RetryDlqResult =
    request.hmppsQueue.retryDlqMessages()

  fun retryAllDlqs() =
    hmppsQueues
      .map { hmppsQueue -> RetryDlqRequest(hmppsQueue) }
      .map { retryDlqRequest -> retryDlqMessages(retryDlqRequest) }

  private fun HmppsQueue.retryDlqMessages(): RetryDlqResult {
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
    log.info("For dlq ${this.dlqName} we found $messageCount messages, attempted to retry ${messages.size}")
    return RetryDlqResult(messageCount, messages.toList())
  }
}

data class RetryDlqRequest(val hmppsQueue: HmppsQueue)
data class RetryDlqResult(val messagesFoundCount: Int, val messages: List<Message>)

internal fun AmazonSQS.countMessagesOnQueue(queueUrl: String): Int =
  this.getQueueAttributes(queueUrl, listOf("ApproximateNumberOfMessages"))
    .let { it.attributes["ApproximateNumberOfMessages"]?.toInt() ?: 0 }
