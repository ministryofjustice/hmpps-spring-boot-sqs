package uk.gov.justice.hmpps.sqs

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.microsoft.applicationinsights.TelemetryClient
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
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

/** Upper bound enforced (by the REST layer) on the maxMessages parameter of getDlqMessages/searchDlqMessages. */
const val MAX_DLQ_MESSAGES_LIMIT = 1000

/** Upper bound enforced (by the REST layer) on the number of messageIds accepted by a single retryDlqMessagesByIds call. */
const val MAX_RETRY_MESSAGE_IDS_LIMIT = 100

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

  /**
   * Read-only/dry-run search of a DLQ. Scans the DLQ, matching each message against [SearchDlqRequest.filter] - a
   * message matches if the filter is null/empty (meaning "return everything"), is found as a substring of the
   * message body, or is an exact match for the message's messageId. No messages are sent or deleted. Intended to
   * be used before [retryDlqMessagesByIds] so an engineer can confirm exactly which messages will be retried
   * before committing to the action.
   */
  open suspend fun searchDlqMessages(request: SearchDlqRequest): SearchDlqResult = request.hmppsQueue.searchDlqMessages(request.filter, request.maxMessages)

  /**
   * Retry only the DLQ messages whose messageId is in [RetryDlqMessagesRequest.messageIds]. Every other message on
   * the DLQ is left untouched. Typically used after inspecting results from [searchDlqMessages]; the messageId can
   * also come from the enriched "sent-to-dlq" telemetry event (see [HmppsErrorVisibilityHandler]).
   */
  open suspend fun retryDlqMessagesByIds(request: RetryDlqMessagesRequest): RetryDlqMessagesResult = request.hmppsQueue.retryDlqMessagesByIds(request.messageIds, request.retriedBy)

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

  /**
   * Scans up to [scanLimit] messages on this queue's DLQ, one at a time, invoking [onMessage] for each distinct
   * message encountered until it returns true (meaning "stop here") or [scanLimit] is reached.
   */
  private suspend fun HmppsQueue.scanDlqMessages(
    scanLimit: Int,
    onMessage: suspend (Message) -> Boolean,
  ) {
    val visibilityTimeoutSeconds = 1 // short, so an unactioned message reappears quickly
    val waitTimeSeconds = 2 // long poll to reduce chance of false-empty response
    val maxConsecutiveEmptyReceives = 3 // retry to reduce chance of false-empty response
    val seenMessageIds = mutableSetOf<String>()
    var consecutiveEmptyReceives = 0
    var scanned = 0
    while (scanned < scanLimit && consecutiveEmptyReceives < maxConsecutiveEmptyReceives) {
      val received = sqsDlqClient!!.receiveMessage(
        ReceiveMessageRequest.builder()
          .queueUrl(dlqUrl)
          .maxNumberOfMessages(1)
          .visibilityTimeout(visibilityTimeoutSeconds)
          .waitTimeSeconds(waitTimeSeconds)
          .messageAttributeNames("All")
          .messageSystemAttributeNames(MessageSystemAttributeName.ALL)
          .build(),
      ).await().messages().firstOrNull()

      if (received == null) {
        consecutiveEmptyReceives++
        continue
      }
      consecutiveEmptyReceives = 0
      scanned++
      if (seenMessageIds.add(received.messageId()) && onMessage(received)) return
    }
  }

  private fun Message.toDlqMessage(bodyMapType: Map<String, Any>): DlqMessage = DlqMessage(
    messageId = messageId(),
    body = gson.fromJson(body(), bodyMapType.javaClass),
    approximateReceiveCount = attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]?.toIntOrNull(),
  )

  private suspend fun HmppsQueue.getDlqMessages(maxMessages: Int): GetDlqResult {
    if (sqsDlqClient == null || dlqUrl == null) return GetDlqResult(0, 0, listOf())

    val messageCount = sqsDlqClient.countMessagesOnQueue(dlqUrl!!).await()
    val messagesToReturnCount = min(messageCount, maxMessages)
    val bodyMapType: Map<String, Any> = HashMap()
    val messages = mutableListOf<DlqMessage>()

    scanDlqMessages(scanLimit = messageCount) { msg ->
      messages.add(msg.toDlqMessage(bodyMapType))
      messages.size >= messagesToReturnCount
    }

    return GetDlqResult(messageCount, messages.size, messages)
  }

  private suspend fun HmppsQueue.searchDlqMessages(filter: String?, maxMessages: Int): SearchDlqResult {
    if (sqsDlqClient == null || dlqUrl == null) return SearchDlqResult(0, 0, listOf())

    val messageCount = sqsDlqClient.countMessagesOnQueue(dlqUrl!!).await()
    val bodyMapType: Map<String, Any> = HashMap()
    val matches = mutableListOf<DlqMessage>()

    scanDlqMessages(scanLimit = messageCount) { msg ->
      if (filter.isNullOrEmpty() || msg.body().contains(filter) || msg.messageId() == filter) {
        matches.add(msg.toDlqMessage(bodyMapType))
      }
      matches.size >= maxMessages
    }

    log.info("For dlq $dlqName searched $messageCount messages, found ${matches.size} matching filter '$filter'")

    return SearchDlqResult(messageCount, matches.size, matches)
  }

  /**
   * Retries (sends to the main queue and removes from the DLQ) only those DLQ messages whose SQS-assigned
   * messageId is in the given list, leaving all other messages on the DLQ untouched.
   */
  private suspend fun HmppsQueue.retryDlqMessagesByIds(messageIds: List<String>, retriedBy: String?): RetryDlqMessagesResult {
    if (sqsDlqClient == null || dlqUrl == null) return RetryDlqMessagesResult(0, 0, listOf(), listOf())

    val messageCount = sqsDlqClient.countMessagesOnQueue(dlqUrl!!).await()
    if (messageIds.isEmpty()) return RetryDlqMessagesResult(messageCount, 0, listOf(), listOf())

    val remainingIds = messageIds.toMutableSet()
    val retriedIds = mutableListOf<String>()
    // The destination (main) queue is FIFO if its name ends in .fifo - in which case every sendMessage must carry
    // the originating message's MessageGroupId (and MessageDeduplicationId, if one was set), or AWS rejects it.
    val isFifoDestination = queueName.endsWith(".fifo")

    scanDlqMessages(scanLimit = messageCount) { msg ->
      if (remainingIds.remove(msg.messageId())) {
        // extend visibility so the send+delete below has time to complete before this message could reappear
        sqsDlqClient.changeMessageVisibility(
          ChangeMessageVisibilityRequest.builder()
            .queueUrl(dlqUrl)
            .receiptHandle(msg.receiptHandle())
            .visibilityTimeout(30)
            .build(),
        ).await()
        sqsClient.sendMessage(
          SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(msg.body())
            .messageAttributes(msg.messageAttributes())
            .apply {
              if (isFifoDestination) {
                messageGroupId(msg.attributes()[MessageSystemAttributeName.MESSAGE_GROUP_ID])
                messageDeduplicationId(msg.attributes()[MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID])
              }
            }
            .build(),
        ).await()
        sqsDlqClient.deleteMessage(
          DeleteMessageRequest.builder()
            .queueUrl(dlqUrl)
            .receiptHandle(msg.receiptHandle())
            .build(),
        ).await()
        retriedIds.add(msg.messageId())
      }
      remainingIds.isEmpty()
    }

    if (retriedIds.isNotEmpty()) {
      log.info("For dlq $dlqName retried ${retriedIds.size} of ${messageIds.size} requested messages")
      telemetryClient?.trackEvent(
        "RetryDLQMessagesById",
        mapOf("dlq-name" to dlqName, "messages-requested" to "${messageIds.size}", "messages-retried" to "${retriedIds.size}") +
          (retriedBy?.let { mapOf("retried-by" to it) } ?: emptyMap()),
        null,
      )
    }

    return RetryDlqMessagesResult(messageCount, retriedIds.size, retriedIds, remainingIds.toList())
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

/** @param approximateReceiveCount SQS's ApproximateReceiveCount - incremented on every receive, including the get/search-dlq-messages scans themselves, not just main-queue delivery attempts. Not always available, hence nullable. */
data class DlqMessage(val body: Map<String, Any>, val messageId: String, val approximateReceiveCount: Int? = null)

/** Dry-run search request - filter matches a message if it is a substring of the message body, or an exact match for the messageId. Null/empty filter matches everything. */
data class SearchDlqRequest(val hmppsQueue: HmppsQueue, val filter: String?, val maxMessages: Int)
data class SearchDlqResult(val messagesFoundCount: Int, val messagesReturnedCount: Int, val messages: List<DlqMessage>)

/** Retry request naming the exact DLQ messages (by messageId) to send back to the main queue.
 * @param retriedBy the identity (if known) of the caller making the request, recorded in telemetry for audit purposes. */
data class RetryDlqMessagesRequest(val hmppsQueue: HmppsQueue, val messageIds: List<String>, val retriedBy: String? = null)

/** @param notFoundMessageIds any requested messageIds that were not present on the DLQ (so could not be retried). */
data class RetryDlqMessagesResult(val messagesFoundCount: Int, val messagesRetriedCount: Int, val retriedMessageIds: List<String>, val notFoundMessageIds: List<String> = listOf())

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
