package uk.gov.justice.hmpps.sqs

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.microsoft.applicationinsights.TelemetryClient
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
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
    if (sqsAsyncDlqClient == null || dlqUrl == null) return RetryDlqResult(0, listOf())
    val messageCount = sqsAsyncDlqClient.countMessagesOnQueue(dlqUrl!!)
    val map: Map<String, Any> = HashMap()

    val messages = (1..messageCount)
      .map { sqsAsyncDlqClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlqUrl).maxNumberOfMessages(1).attributeNames(QueueAttributeName.ALL).build()).await() }
      .mapNotNull { it.messages().firstOrNull() }
      .map { msg ->
        sqsAsyncClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(msg.body()).messageAttributes(msg.messageAttributes()).build())
        sqsAsyncDlqClient.deleteMessage(DeleteMessageRequest.builder().queueUrl(dlqUrl).receiptHandle(msg.receiptHandle()).build())
        DlqMessage(messageId = msg.messageId(), body = gson.fromJson(msg.body(), map.javaClass))
      }

    messageCount.takeIf { it > 0 }
      ?.also { log.info("For dlq ${this.dlqName} we found $messageCount messages, attempted to retry ${messages.size}") }
      ?.also { telemetryClient?.trackEvent("RetryDLQ", mapOf("dlq-name" to dlqName, "messages-found" to "$messageCount", "messages-retried" to "${messages.size}"), null) }

    return RetryDlqResult(messageCount, messages)
  }

  private suspend fun HmppsAsyncQueue.getDlqMessages(maxMessages: Int): GetDlqResult {
    if (sqsAsyncDlqClient == null || dlqUrl == null) return GetDlqResult(0, 0, listOf())

    val messageCount = sqsAsyncDlqClient.countMessagesOnQueue(dlqUrl!!)
    val messagesToReturnCount = min(messageCount, maxMessages)
    val map: Map<String, Any> = HashMap()

    val messages = (1..messagesToReturnCount)
      .asFlow()
      .map { sqsAsyncDlqClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlqUrl).maxNumberOfMessages(1).build()).await() }
      .mapNotNull { it.messages().firstOrNull() }
      .map { msg -> DlqMessage(messageId = msg.messageId(), body = gson.fromJson(msg.body(), map.javaClass)) }
      .toList()

    return GetDlqResult(messageCount, messagesToReturnCount, messages)
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
  this.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES).build())
    .await()
    .let {
      it.attributes()[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toInt() ?: 0
    }
