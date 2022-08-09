package uk.gov.justice.hmpps.sqs

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest as AwsPurgeQueueRequest

open class HmppsAsyncQueueService(
  private val telemetryClient: TelemetryClient?,
  hmppsAsyncTopicFactory: HmppsAsyncTopicFactory,
  hmppsAsyncQueueFactory: HmppsAsyncQueueFactory,
  hmppsSqsProperties: HmppsSqsProperties,
) {

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val mapClassType = mapOf<String, Any>().javaClass
  }

  private val hmppsAsyncTopics: List<HmppsAsyncTopic> = hmppsAsyncTopicFactory.createHmppsAsyncTopics(hmppsSqsProperties)
  private val hmppsAsyncQueues: List<HmppsAsyncQueue> = hmppsAsyncQueueFactory.createHmppsAsyncQueues(hmppsSqsProperties, hmppsAsyncTopics)

  open fun findByQueueId(queueId: String) = hmppsAsyncQueues.associateBy { it.id }.getOrDefault(queueId, null)
  open fun findByQueueName(queueName: String) = hmppsAsyncQueues.associateBy { it.queueName }.getOrDefault(queueName, null)
  open fun findByDlqName(dlqName: String) = hmppsAsyncQueues.associateBy { it.dlqName }.getOrDefault(dlqName, null)

  open fun findByTopicId(topicId: String) = hmppsAsyncTopics.associateBy { it.id }.getOrDefault(topicId, null)

  open suspend fun retryDlqMessages(request: RetryAsyncDlqRequest): RetryDlqResult =
    request.hmppsQueue.retryDlqMessages()

  open suspend fun getDlqMessages(request: GetAsyncDlqRequest): GetDlqResult =
    request.hmppsQueue.getDlqMessages(request.maxMessages)

  open suspend fun retryAllDlqs() =
    hmppsAsyncQueues
      .map { hmppsQueue -> RetryAsyncDlqRequest(hmppsQueue) }
      .map { retryDlqRequest -> retryDlqMessages(retryDlqRequest) }

  private suspend fun HmppsAsyncQueue.retryDlqMessages(): RetryDlqResult {
    if (sqsAsyncDlqClient == null || dlqUrl == null) return RetryDlqResult(0, mutableListOf())
    val messageCount = sqsAsyncDlqClient.countMessagesOnQueue(dlqUrl!!)
    val messages = mutableListOf<Message.Builder>()
    repeat(messageCount) {
      sqsAsyncDlqClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlqUrl).maxNumberOfMessages(1).attributeNames(QueueAttributeName.ALL).build())
        .thenApply { it.messages().firstOrNull() }
        .thenApply { msg ->
          msg?.run {
            sqsAsyncClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(msg.body()).messageAttributes(msg.messageAttributes()).build())
            sqsAsyncDlqClient.deleteMessage(DeleteMessageRequest.builder().queueUrl(dlqUrl).receiptHandle(msg.receiptHandle()).build())
            messages += msg.toBuilder()
          }
        }
    }
    messageCount.takeIf { it > 0 }
      ?.also { log.info("For dlq ${this.dlqName} we found $messageCount messages, attempted to retry ${messages.size}") }
      ?.also { telemetryClient?.trackEvent("RetryDLQ", mapOf("dlq-name" to dlqName, "messages-found" to "$messageCount", "messages-retried" to "${messages.size}"), null) }
    return RetryDlqResult(messageCount, messages.toList())
  }

  private suspend fun HmppsAsyncQueue.getDlqMessages(maxMessages: Int): GetDlqResult {
    if (sqsAsyncDlqClient == null || dlqUrl == null) return GetDlqResult(0, 0, listOf())

    val messages = mutableListOf<DlqMessage>()
    val messageCount = sqsAsyncDlqClient.countMessagesOnQueue(dlqUrl!!)
    val messagesToReturnCount = min(messageCount, maxMessages)

    (1..messagesToReturnCount).map {
        sqsAsyncDlqClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlqUrl).maxNumberOfMessages(1).build())
          .thenApply { it.messages().firstOrNull() }
          .thenApply { msg ->
            msg?.run {
              messages += DlqMessage(messageId = msg.messageId(), body = gson.fromJson(msg.body(), mapClassType))
            }
          }
      }.toTypedArray()
    // CompletableFuture.allOf(*messageFutures) TODO do I need this? Or does suspend magic just work and I can switch back to repeat(messagesToReturnCount)?
    return GetDlqResult(messageCount, messagesToReturnCount, messages.toList())
  }

  open suspend fun purgeQueue(request: PurgeAsyncQueueRequest): PurgeQueueResult =
    with(request) {
      sqsClient.countMessagesOnQueue(queueUrl)
        .takeIf { it > 0 }
        ?.also { sqsClient.purgeQueue(AwsPurgeQueueRequest.builder().queueUrl(queueUrl).build()) }
        ?.also { log.info("For queue $queueName attempted to purge $it messages from queue") }
        ?.also { telemetryClient?.trackEvent("PurgeQueue", mapOf("queue-name" to queueName, "messages-found" to "$it"), null) }
        ?.let { PurgeQueueResult(it) }
        ?: PurgeQueueResult(0)
    }

  open fun findQueueToPurge(queueName: String): PurgeAsyncQueueRequest? =
    findByQueueName(queueName)
      ?.let { hmppsQueue -> PurgeAsyncQueueRequest(hmppsQueue.queueName, hmppsQueue.sqsAsyncClient, hmppsQueue.queueUrl) }
      ?: findByDlqName(queueName)
        ?.let { hmppsQueue -> PurgeAsyncQueueRequest(hmppsQueue.dlqName!!, hmppsQueue.sqsAsyncDlqClient!!, hmppsQueue.dlqUrl!!) }
}

data class RetryAsyncDlqRequest(val hmppsQueue: HmppsAsyncQueue)

data class GetAsyncDlqRequest(val hmppsQueue: HmppsAsyncQueue, val maxMessages: Int)

data class PurgeAsyncQueueRequest(val queueName: String, val sqsClient: SqsAsyncClient, val queueUrl: String)

internal suspend fun SqsAsyncClient.countMessagesOnQueue(queueUrl: String): Int =
  suspendCoroutine {
    this.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES).build())
      .thenApply { it.attributes()[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toInt() ?: 0 }
  }
