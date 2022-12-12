package uk.gov.justice.hmpps.sqs

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import kotlin.math.min
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest as AwsPurgeQueueRequest

class MissingQueueException(message: String) : RuntimeException(message)
class MissingTopicException(message: String) : RuntimeException(message)

open class HmppsQueueService(
  private val telemetryClient: TelemetryClient?,
  hmppsTopicService: HmppsTopicService,
  hmppsQueueFactory: HmppsQueueFactory,
  hmppsSqsProperties: HmppsSqsProperties,
) {

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
  }

  private val hmppsQueues: List<HmppsQueue> = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, hmppsTopicService.hmppsTopics, hmppsTopicService.hmppsAsyncTopics)

  open fun findByQueueId(queueId: String) = hmppsQueues.associateBy { it.id }.getOrDefault(queueId, null)
  open fun findByQueueName(queueName: String) = hmppsQueues.associateBy { it.queueName }.getOrDefault(queueName, null)
  open fun findByDlqName(dlqName: String) = hmppsQueues.associateBy { it.dlqName }.getOrDefault(dlqName, null)

  open fun retryDlqMessages(request: RetryDlqRequest): RetryDlqResult =
    request.hmppsQueue.retryDlqMessages()

  open fun getDlqMessages(request: GetDlqRequest): GetDlqResult =
    request.hmppsQueue.getDlqMessages(request.maxMessages)

  open fun retryAllDlqs() =
    hmppsQueues
      .map { hmppsQueue -> RetryDlqRequest(hmppsQueue) }
      .map { retryDlqRequest -> retryDlqMessages(retryDlqRequest) }

  private fun HmppsQueue.retryDlqMessages(): RetryDlqResult {
    if (sqsDlqClient == null || dlqUrl == null) return RetryDlqResult(0, mutableListOf())
    val messageCount = sqsDlqClient.countMessagesOnQueue(dlqUrl!!)
    val messages = mutableListOf<DlqMessage>()
    val map: Map<String, Any> = HashMap()

    repeat(messageCount) {
      sqsDlqClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlqUrl).maxNumberOfMessages(1).messageAttributeNames("All").build()).messages().firstOrNull()
        ?.also { msg ->
          sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(msg.body()).messageAttributes(msg.messageAttributes()).build())
          sqsDlqClient.deleteMessage(DeleteMessageRequest.builder().queueUrl(dlqUrl).receiptHandle(msg.receiptHandle()).build())
          messages += DlqMessage(messageId = msg.messageId(), body = gson.fromJson(msg.body(), map.javaClass))
        }
    }
    if (messageCount > 0) {
      log.info("For dlq ${this.dlqName} we found $messageCount messages, attempted to retry ${messages.size}")
      telemetryClient?.trackEvent("RetryDLQ", mapOf("dlq-name" to dlqName, "messages-found" to "$messageCount", "messages-retried" to "${messages.size}"), null)
    }

    return RetryDlqResult(messageCount, messages.toList())
  }

  private fun HmppsQueue.getDlqMessages(maxMessages: Int): GetDlqResult {
    if (sqsDlqClient == null || dlqUrl == null) return GetDlqResult(0, 0, mutableListOf())

    val messages = mutableListOf<DlqMessage>()
    val messageCount = sqsDlqClient.countMessagesOnQueue(dlqUrl!!)
    val messagesToReturnCount = min(messageCount, maxMessages)

    repeat(messagesToReturnCount) {
      sqsDlqClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlqUrl).maxNumberOfMessages(1).build()).messages().firstOrNull()
        ?.also { msg ->
          val map: Map<String, Any> = HashMap()
          messages += DlqMessage(messageId = msg.messageId(), body = gson.fromJson(msg.body(), map.javaClass))
        }
    }

    return GetDlqResult(messageCount, messagesToReturnCount, messages.toList())
  }

  open fun purgeQueue(request: PurgeQueueRequest): PurgeQueueResult =
    with(request) {
      val messageCount = sqsClient.countMessagesOnQueue(queueUrl)
      return if (messageCount > 0) {
        sqsClient.purgeQueue(AwsPurgeQueueRequest.builder().queueUrl(queueUrl).build())
        log.info("For queue $queueName attempted to purge $messageCount messages from queue")
        telemetryClient?.trackEvent("PurgeQueue", mapOf("queue-name" to queueName, "messages-found" to "$messageCount"), null)
        PurgeQueueResult(messageCount)
      } else {
        PurgeQueueResult(0)
      }
    }

  open fun findQueueToPurge(queueName: String): PurgeQueueRequest? =
    findByQueueName(queueName)
      ?.let { hmppsQueue -> PurgeQueueRequest(hmppsQueue.queueName, hmppsQueue.sqsClient, hmppsQueue.queueUrl) }
      ?: findByDlqName(queueName)
        ?.let { hmppsQueue -> PurgeQueueRequest(hmppsQueue.dlqName!!, hmppsQueue.sqsDlqClient!!, hmppsQueue.dlqUrl!!) }
}
data class RetryDlqRequest(val hmppsQueue: HmppsQueue)
data class RetryDlqResult(val messagesFoundCount: Int, val messages: List<DlqMessage>)
data class GetDlqRequest(val hmppsQueue: HmppsQueue, val maxMessages: Int)
data class GetDlqResult(val messagesFoundCount: Int, val messagesReturnedCount: Int, val messages: List<DlqMessage>)
data class DlqMessage(val body: Map<String, Any>, val messageId: String)

data class PurgeQueueRequest(val queueName: String, val sqsClient: SqsClient, val queueUrl: String)
data class PurgeQueueResult(val messagesFoundCount: Int)

internal fun SqsClient.countMessagesOnQueue(queueUrl: String): Int =
  this.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES).build())
    .let {
      it.attributes()[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toInt() ?: 0
    }
