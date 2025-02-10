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
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES
import software.amazon.awssdk.services.sqs.model.QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.StartMessageMoveTaskRequest
import java.util.concurrent.CompletableFuture
import kotlin.math.min
import software.amazon.awssdk.services.sns.model.MessageAttributeValue as SnsMessageAttributeValue
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue as SqsMessageAttributeValue
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest as AwsPurgeQueueRequest

class MissingQueueException(message: String) : RuntimeException(message)
class MissingTopicException(message: String) : RuntimeException(message)

const val AUDIT_ID = "audit"

open class HmppsQueueService(
  private val telemetryClient: TelemetryClient?,
  hmppsTopicFactory: HmppsTopicFactory,
  hmppsQueueFactory: HmppsQueueFactory,
  hmppsSqsProperties: HmppsSqsProperties,
) {

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
  }

  private val hmppsTopics: List<HmppsTopic> = hmppsTopicFactory.createHmppsTopics(hmppsSqsProperties)
  private val hmppsQueues: List<HmppsQueue> = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, hmppsTopics)

  open fun findByQueueId(queueId: String): HmppsQueue? = hmppsQueues.find { it.id == queueId }
  open fun findByQueueName(queueName: String): HmppsQueue? = hmppsQueues.find { it.queueName == queueName }
  open fun findByDlqName(dlqName: String): HmppsQueue? = hmppsQueues.find { it.dlqName == dlqName }
  open fun findByTopicId(topicId: String): HmppsTopic? = hmppsTopics.find { it.id == topicId }

  open suspend fun retryDlqMessages(request: RetryDlqRequest): RetryDlqResult = request.hmppsQueue.retryDlqMessages()

  open suspend fun getDlqMessages(request: GetDlqRequest): GetDlqResult = request.hmppsQueue.getDlqMessages(request.maxMessages)

  open suspend fun retryAllDlqs() = hmppsQueues
    .map { hmppsQueue -> RetryDlqRequest(hmppsQueue) }
    .map { retryDlqRequest -> retryDlqMessages(retryDlqRequest) }

  private suspend fun HmppsQueue.retryDlqMessages(): RetryDlqResult {
    if (sqsDlqClient == null || dlqUrl == null) {
      return RetryDlqResult(0)
    }

    val messageCount = sqsDlqClient.countMessagesOnQueue(dlqUrl!!).await()

    if (messageCount > 0) {
      sqsDlqClient.startMessageMoveTask(
        StartMessageMoveTaskRequest
          .builder()
          .sourceArn(dlqArn)
          .destinationArn(queueArn)
          .build(),
      ).await()

      log.info("For dlq ${this.dlqName} we found $messageCount messages")
      telemetryClient?.trackEvent("RetryDLQ", mapOf("dlq-name" to dlqName, "messages-found" to "$messageCount"), null)
    }

    return RetryDlqResult(messageCount)
  }

  private suspend fun HmppsQueue.getDlqMessages(maxMessages: Int): GetDlqResult {
    if (sqsDlqClient == null || dlqUrl == null) return GetDlqResult(0, 0, listOf())

    val messageCount = sqsDlqClient.countMessagesOnQueue(dlqUrl!!).await()
    val messagesToReturnCount = min(messageCount, maxMessages)
    val map: Map<String, Any> = HashMap()

    val messages = (1..messagesToReturnCount)
      .asFlow()
      .map {
        sqsDlqClient.receiveMessage(
          ReceiveMessageRequest.builder()
            .queueUrl(dlqUrl)
            .maxNumberOfMessages(1)
            .visibilityTimeout(1)
            .build(),
        ).await()
      }
      .mapNotNull { it.messages().firstOrNull() }
      .map { msg -> DlqMessage(messageId = msg.messageId(), body = gson.fromJson(msg.body(), map.javaClass)) }
      .toList()

    return GetDlqResult(messageCount, messagesToReturnCount, messages)
  }

  open suspend fun purgeQueue(request: PurgeQueueRequest): PurgeQueueResult = with(request) {
    val messageCount = sqsClient.countMessagesOnQueue(queueUrl).await()
    return if (messageCount > 0) {
      sqsClient.purgeQueue(AwsPurgeQueueRequest.builder().queueUrl(queueUrl).build())
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

  open fun findQueueToPurge(queueName: String): PurgeQueueRequest? = hmppsQueues.find { it.id != AUDIT_ID && it.queueName == queueName }
    ?.let { hmppsQueue -> PurgeQueueRequest(hmppsQueue.queueName, hmppsQueue.sqsClient, hmppsQueue.queueUrl) }
    ?: findByDlqName(queueName)
      ?.let { hmppsQueue -> PurgeQueueRequest(hmppsQueue.dlqName!!, hmppsQueue.sqsDlqClient!!, hmppsQueue.dlqUrl!!) }
}
data class RetryDlqRequest(val hmppsQueue: HmppsQueue)
data class RetryDlqResult(val messagesFoundCount: Int)
data class GetDlqRequest(val hmppsQueue: HmppsQueue, val maxMessages: Int)
data class GetDlqResult(val messagesFoundCount: Int, val messagesReturnedCount: Int, val messages: List<DlqMessage>)
data class DlqMessage(val body: Map<String, Any>, val messageId: String)
data class PurgeQueueRequest(val queueName: String, val sqsClient: SqsAsyncClient, val queueUrl: String)
data class PurgeQueueResult(val messagesFoundCount: Int)

/**
 * Count the approximate number of messages currently on the queue.  This only takes into account visible messages.
 * When a message is read from a queue it is marked as invisible and then either acknowledged and removed from the queue
 * or not acknowledged (after the visibility timeout has passed) and made visible again so that it can be retried.
 * After dlqMaxReceiveCount tries it is then moved onto the dead letter queue. See also countAllMessagesOnQueue that
 * counts the number of messages that are both visible and invisible.
 *
 * See https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html for further information.
 *
 * @param queueUrl String
 * @return CompletableFuture<Int>
 */
fun SqsAsyncClient.countMessagesOnQueue(queueUrl: String): CompletableFuture<Int> = this.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl).attributeNames(APPROXIMATE_NUMBER_OF_MESSAGES).build())
  .thenApply {
    it.attributes()[APPROXIMATE_NUMBER_OF_MESSAGES]?.toInt() ?: 0
  }

/**
 * Count the approximate number of both visible and invisible messages currently on the queue.  This takes into account
 * messages that have been read from a queue and haven't been acknowledged yet.  See also countMessagesOnQueue that
 * only counts the number of messages that are visible on the queue.
 *
 * @param queueUrl String
 * @return CompletableFuture<Int>
 */
fun SqsAsyncClient.countAllMessagesOnQueue(queueUrl: String): CompletableFuture<Int> = this.getQueueAttributes(
  GetQueueAttributesRequest.builder()
    .queueUrl(queueUrl)
    .attributeNames(APPROXIMATE_NUMBER_OF_MESSAGES, APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
    .build(),
)
  .thenApply {
    (it.attributes()[APPROXIMATE_NUMBER_OF_MESSAGES]?.toInt() ?: 0) +
      (it.attributes()[APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE]?.toInt() ?: 0)
  }

fun PublishRequest.Builder.eventTypeMessageAttributes(eventType: String, noTracing: Boolean = false): PublishRequest.Builder = messageAttributes(eventTypeSnsMap(eventType, noTracing))

fun eventTypeSnsMap(eventType: String, noTracing: Boolean = false) = mapOf("eventType" to SnsMessageAttributeValue.builder().dataType("String").stringValue(eventType).build()) +
  if (noTracing) mapOf("noTracing" to SnsMessageAttributeValue.builder().dataType("String").stringValue("true").build()) else emptyMap()

fun SendMessageRequest.Builder.eventTypeMessageAttributes(eventType: String, noTracing: Boolean = false): SendMessageRequest.Builder = messageAttributes(eventTypeSqsMap(eventType, noTracing))

fun eventTypeSqsMap(eventType: String, noTracing: Boolean = false) = mapOf("eventType" to SqsMessageAttributeValue.builder().dataType("String").stringValue(eventType).build()) +
  if (noTracing) mapOf("noTracing" to SqsMessageAttributeValue.builder().dataType("String").stringValue("true").build()) else emptyMap()
