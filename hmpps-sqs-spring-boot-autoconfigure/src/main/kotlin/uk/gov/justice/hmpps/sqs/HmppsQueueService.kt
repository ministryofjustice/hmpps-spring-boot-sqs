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

  private val hmppsQueues: List<HmppsQueue> = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, hmppsTopicService.hmppsTopics)

  open fun findByQueueId(queueId: String) = hmppsQueues.associateBy { it.id }.getOrDefault(queueId, null)
  open fun findByQueueName(queueName: String) = hmppsQueues.associateBy { it.queueName }.getOrDefault(queueName, null)
  open fun findByDlqName(dlqName: String) = hmppsQueues.associateBy { it.dlqName }.getOrDefault(dlqName, null)

  open suspend fun retryDlqMessages(request: RetryDlqRequest): RetryDlqResult =
    request.hmppsQueue.retryDlqMessages()

  open suspend fun getDlqMessages(request: GetDlqRequest): GetDlqResult =
    request.hmppsQueue.getDlqMessages(request.maxMessages)

  open suspend fun retryAllDlqs() =
    hmppsQueues
      .map { hmppsQueue -> RetryDlqRequest(hmppsQueue) }
      .map { retryDlqRequest -> retryDlqMessages(retryDlqRequest) }

  private suspend fun HmppsQueue.retryDlqMessages(): RetryDlqResult {
    if (sqsDlqClient == null || dlqUrl == null) return RetryDlqResult(0, listOf())

    val messageCount = sqsDlqClient.countMessagesOnQueue(dlqUrl!!)
    val map: Map<String, Any> = HashMap()

    val messages = (1..messageCount)
      .asFlow()
      .map { sqsDlqClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlqUrl).maxNumberOfMessages(1).messageAttributeNames("All").build()) }
      .mapNotNull { it.await().messages().firstOrNull() }
      .map { msg ->
        sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(msg.body()).messageAttributes(msg.messageAttributes()).build()).await()
        sqsDlqClient.deleteMessage(DeleteMessageRequest.builder().queueUrl(dlqUrl).receiptHandle(msg.receiptHandle()).build()).await()
        DlqMessage(messageId = msg.messageId(), body = gson.fromJson(msg.body(), map.javaClass))
      }.toList()

    if (messageCount > 0) {
      log.info("For dlq ${this.dlqName} we found $messageCount messages, attempted to retry ${messages.size}")
      telemetryClient?.trackEvent("RetryDLQ", mapOf("dlq-name" to dlqName, "messages-found" to "$messageCount", "messages-retried" to "${messages.size}"), null)
    }

    return RetryDlqResult(messageCount, messages)
  }

  private suspend fun HmppsQueue.getDlqMessages(maxMessages: Int): GetDlqResult {
    if (sqsDlqClient == null || dlqUrl == null) return GetDlqResult(0, 0, listOf())

    val messageCount = sqsDlqClient.countMessagesOnQueue(dlqUrl!!)
    val messagesToReturnCount = min(messageCount, maxMessages)
    val map: Map<String, Any> = HashMap()

    val messages = (1..messagesToReturnCount)
      .asFlow()
      .map { sqsDlqClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(dlqUrl).maxNumberOfMessages(1).build()).await() }
      .mapNotNull { it.messages().firstOrNull() }
      .map { msg -> DlqMessage(messageId = msg.messageId(), body = gson.fromJson(msg.body(), map.javaClass)) }
      .toList()

    return GetDlqResult(messageCount, messagesToReturnCount, messages)
  }

  open suspend fun purgeQueue(request: PurgeQueueRequest): PurgeQueueResult =
    with(request) {
      val messageCount = sqsAsyncClient.countMessagesOnQueue(queueUrl)
      return if (messageCount > 0) {
        sqsAsyncClient.purgeQueue(AwsPurgeQueueRequest.builder().queueUrl(queueUrl).build())
          .await()
          .let { PurgeQueueResult(messageCount) }
          .also {
            log.info("For queue $queueName attempted to purge $messageCount messages from queue")
            telemetryClient?.trackEvent("PurgeQueue", mapOf("queue-name" to queueName, "messages-found" to "$messageCount"), null)
          }
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
data class PurgeQueueRequest(val queueName: String, val sqsAsyncClient: SqsAsyncClient, val queueUrl: String)
data class PurgeQueueResult(val messagesFoundCount: Int)

internal suspend fun SqsAsyncClient.countMessagesOnQueue(queueUrl: String): Int =
  this.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES).build())
    .await()
    .let {
      it.attributes()[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toInt() ?: 0
    }
