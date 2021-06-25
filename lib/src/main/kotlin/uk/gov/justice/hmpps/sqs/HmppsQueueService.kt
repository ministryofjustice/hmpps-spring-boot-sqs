package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.DeleteMessageRequest
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import com.amazonaws.services.sqs.model.PurgeQueueRequest as AwsPurgeQueueRequest

class HmppsQueueService(
  private val telemetryClient: TelemetryClient?,
  private val hmppsQueueFactory: HmppsQueueFactory,
  hmppsQueueProperties: HmppsQueueProperties,
) {

  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }

  private val hmppsQueues: MutableList<HmppsQueue> = mutableListOf()

  init {
    hmppsQueues.addAll(hmppsQueueFactory.registerHmppsQueues(hmppsQueueProperties))
  }

  /*
   * This method is for creating a queue outside of HmppsQueueProperties, for example if you wish to customise your AmazonSQS instances but still receive the benefits of registering with the queue library.
   * Note that beans will be created for the AmazonSQS clients you pass in.
   */
  fun createHmppsQueue(id: String, sqsClient: AmazonSQS, queueName: String, sqsDlqClient: AmazonSQS, dlqName: String) =
    hmppsQueues.add(hmppsQueueFactory.registerHmppsQueue(id, sqsClient, queueName, sqsDlqClient, dlqName))

  fun findByQueueId(queueId: String) = hmppsQueues.associateBy { it.id }.getOrDefault(queueId, null)
  fun findByQueueName(queueName: String) = hmppsQueues.associateBy { it.queueName }.getOrDefault(queueName, null)
  fun findByDlqName(dlqName: String) = hmppsQueues.associateBy { it.dlqName }.getOrDefault(dlqName, null)

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
        ?.let { msg ->
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
