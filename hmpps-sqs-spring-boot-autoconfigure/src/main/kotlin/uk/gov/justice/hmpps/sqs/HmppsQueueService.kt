package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.DeleteMessageRequest
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import com.amazonaws.services.sqs.model.PurgeQueueRequest as AwsPurgeQueueRequest

class MissingQueueException(message: String) : RuntimeException(message)
class MissingTopicException(message: String) : RuntimeException(message)

class HmppsQueueService(
  private val telemetryClient: TelemetryClient?,
  hmppsTopicFactory: HmppsTopicFactory,
  hmppsQueueFactory: HmppsQueueFactory,
  hmppsSqsProperties: HmppsSqsProperties,
) {

  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }

  private val hmppsTopics: List<HmppsTopic> = hmppsTopicFactory.createHmppsTopics(hmppsSqsProperties)
  private val hmppsQueues: List<HmppsQueue> = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, hmppsTopics)

  fun findByQueueId(queueId: String) = hmppsQueues.associateBy { it.id }.getOrDefault(queueId, null)
  fun findByQueueName(queueName: String) = hmppsQueues.associateBy { it.queueName }.getOrDefault(queueName, null)
  fun findByDlqName(dlqName: String) = hmppsQueues.associateBy { it.dlqName }.getOrDefault(dlqName, null)

  fun findByTopicId(topicId: String) = hmppsTopics.associateBy { it.id }.getOrDefault(topicId, null)

  fun retryDlqMessages(request: RetryDlqRequest): RetryDlqResult =
    request.hmppsQueue.retryDlqMessages()

  fun retryAllDlqs() =
    hmppsQueues
      .map { hmppsQueue -> RetryDlqRequest(hmppsQueue) }
      .map { retryDlqRequest -> retryDlqMessages(retryDlqRequest) }

  private fun HmppsQueue.retryDlqMessages(): RetryDlqResult {
    val messageCount = sqsDlqClient.countMessagesOnQueue(dlqUrl)
    val messages = mutableListOf<Message>()
    repeat(messageCount) {
      sqsDlqClient.receiveMessage(ReceiveMessageRequest(dlqUrl).withMaxNumberOfMessages(1)).messages.firstOrNull()
        ?.also { msg ->
          sqsClient.sendMessage(queueUrl, msg.body)
          sqsDlqClient.deleteMessage(DeleteMessageRequest(dlqUrl, msg.receiptHandle))
          messages += msg
        }
    }
    messageCount.takeIf { it > 0 }
      ?.also { log.info("For dlq ${this.dlqName} we found $messageCount messages, attempted to retry ${messages.size}") }
      ?.also { telemetryClient?.trackEvent("RetryDLQ", mapOf("dlq-name" to dlqName, "messages-found" to "$messageCount", "messages-retried" to "${messages.size}"), null) }
    return RetryDlqResult(messageCount, messages.toList())
  }

  fun purgeQueue(request: PurgeQueueRequest): PurgeQueueResult =
    with(request) {
      sqsClient.countMessagesOnQueue(queueUrl)
        .takeIf { it > 0 }
        ?.also { sqsClient.purgeQueue(AwsPurgeQueueRequest(queueUrl)) }
        ?.also { log.info("For queue $queueName attempted to purge $it messages from queue") }
        ?.also { telemetryClient?.trackEvent("PurgeQueue", mapOf("queue-name" to queueName, "messages-found" to "$it"), null) }
        ?.let { PurgeQueueResult(it) }
        ?: PurgeQueueResult(0)
    }

  fun findQueueToPurge(queueName: String): PurgeQueueRequest? =
    findByQueueName(queueName)
      ?.let { hmppsQueue -> PurgeQueueRequest(hmppsQueue.queueName, hmppsQueue.sqsClient, hmppsQueue.queueUrl) }
      ?: findByDlqName(queueName)
        ?.let { hmppsQueue -> PurgeQueueRequest(hmppsQueue.dlqName, hmppsQueue.sqsDlqClient, hmppsQueue.dlqUrl) }
}

data class RetryDlqRequest(val hmppsQueue: HmppsQueue)
data class RetryDlqResult(val messagesFoundCount: Int, val messages: List<Message>)

data class PurgeQueueRequest(val queueName: String, val sqsClient: AmazonSQS, val queueUrl: String)
data class PurgeQueueResult(val messagesFoundCount: Int)

internal fun AmazonSQS.countMessagesOnQueue(queueUrl: String): Int =
  this.getQueueAttributes(queueUrl, listOf("ApproximateNumberOfMessages"))
    .let { it.attributes["ApproximateNumberOfMessages"]?.toInt() ?: 0 }