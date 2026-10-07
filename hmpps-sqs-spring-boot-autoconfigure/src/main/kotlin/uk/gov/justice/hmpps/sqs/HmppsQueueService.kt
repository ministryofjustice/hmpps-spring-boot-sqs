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

// Long enough to comfortably cover the sendMessage + deleteMessage calls made against every matched message in a
// single scan batch in HmppsQueueService.retryDlqMessagesByIds - the whole batch (up to DLQ_RECEIVE_BATCH_SIZE
// messages) is given this visibility timeout immediately on receipt, before any match is identified or processed,
// so a match discovered later in the batch can't have its receipt handle go stale while earlier matches in the
// same batch are still being sent/deleted. Any scanned message that doesn't turn out to be a match has its
// visibility released immediately (see retryDlqMessagesByIds), so this long timeout only actually delays
// reappearance of messages that are in the middle of being retried, not the whole batch.
private const val RETRY_VISIBILITY_TIMEOUT_SECONDS = 30

// Deliberately short visibility timeout used while scanning a DLQ (getDlqMessages/searchDlqMessages), so that any
// message which isn't actually returned reappears quickly for normal queue processing rather than being held
// invisible for longer than necessary.
private const val SCAN_VISIBILITY_TIMEOUT_SECONDS = 1

// The maximum number of messages SQS allows per receiveMessage call.
private const val DLQ_RECEIVE_BATCH_SIZE = 10

// SQS samples only a subset of its servers per receiveMessage call (short polling), so an empty response does not
// guarantee no messages remain - enabling a short amount of long polling considerably reduces (without fully
// eliminating) the chance of a false-empty response while a scan is still in progress.
private const val SCAN_WAIT_TIME_SECONDS = 2

// Number of consecutive empty receiveMessage responses tolerated before a scan concludes it has exhausted the
// DLQ. Stopping after a single empty response risks truncating get/search results and incorrectly reporting
// requested retry ids as not found; a few retries makes that far less likely.
private const val MAX_CONSECUTIVE_EMPTY_RECEIVES = 3

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
   * Scans up to [scanLimit] messages on this queue's DLQ, in batches of up to [DLQ_RECEIVE_BATCH_SIZE] (the SQS
   * maximum per receiveMessage call), invoking [onMessage] once for each distinct message encountered. Duplicates -
   * which can occur if a message's visibility expires and it is redelivered mid-scan - are only passed to
   * [onMessage] once. Once [onMessage] returns true (meaning "stop here"), every other message already received in
   * that same batch is still passed to [onMessage] (so, for example, retryDlqMessagesByIds gets a chance to release
   * the visibility of batch-mates that turn out not to be matches) before scanning stops; no further batches are
   * received after that. Scanning also stops once [scanLimit] messages have been received in total, or once
   * [MAX_CONSECUTIVE_EMPTY_RECEIVES] consecutive receives come back empty (SQS short-polls by default, so a single
   * empty response doesn't guarantee the DLQ is actually exhausted) - whichever happens first.
   *
   * Receiving in batches (rather than one message per call, as before) both reduces the number of round trips to
   * SQS and shortens the overall scan, reducing the chance of a message's visibility timeout lapsing mid-scan. Every
   * message received in a batch is given [visibilityTimeoutSeconds] immediately, before any of them are inspected
   * or acted on by [onMessage] - this matters for retryDlqMessagesByIds, where it ensures a match found later in the
   * batch doesn't have its receipt handle go stale while earlier matches in the same batch are still being
   * sent/deleted.
   */
  private suspend fun HmppsQueue.scanDlqMessages(
    scanLimit: Int,
    visibilityTimeoutSeconds: Int = SCAN_VISIBILITY_TIMEOUT_SECONDS,
    onMessage: suspend (Message) -> Boolean,
  ) {
    val seenMessageIds = mutableSetOf<String>()
    var remainingToScan = scanLimit
    var consecutiveEmptyReceives = 0
    var stopRequested = false
    while (!stopRequested && remainingToScan > 0 && consecutiveEmptyReceives < MAX_CONSECUTIVE_EMPTY_RECEIVES) {
      val batchSize = min(DLQ_RECEIVE_BATCH_SIZE, remainingToScan)
      val received = sqsDlqClient!!.receiveMessage(
        ReceiveMessageRequest.builder()
          .queueUrl(dlqUrl)
          .maxNumberOfMessages(batchSize)
          .visibilityTimeout(visibilityTimeoutSeconds)
          .waitTimeSeconds(SCAN_WAIT_TIME_SECONDS)
          .messageAttributeNames("All")
          .messageSystemAttributeNames(MessageSystemAttributeName.ALL)
          .build(),
      ).await().messages()
      if (received.isEmpty()) {
        consecutiveEmptyReceives++
        continue
      }
      consecutiveEmptyReceives = 0
      remainingToScan -= received.size
      for (msg in received) {
        if (seenMessageIds.add(msg.messageId()) && onMessage(msg)) stopRequested = true
      }
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
      if (messages.size < messagesToReturnCount) messages.add(msg.toDlqMessage(bodyMapType))
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
      if (matches.size < maxMessages && (filter.isNullOrEmpty() || msg.body().contains(filter) || msg.messageId() == filter)) {
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

    // Each matched message is sent/deleted immediately, as part of the same scan pass that receives it, rather
    // than being collected into a list first - this keeps the gap between receipt and deletion as small as
    // possible, so the receipt handle doesn't go stale (and the message doesn't become concurrently visible to
    // others) before we act on it. Every message in a scan batch (not just matches) is given the longer
    // RETRY_VISIBILITY_TIMEOUT_SECONDS as soon as it's received (see scanDlqMessages), since matches are identified
    // and processed one at a time within the batch - without this, a match found later in the batch could have its
    // receipt handle expire while earlier matches in the same batch are still being sent/deleted. Any message that
    // turns out not to be a requested id has its visibility released immediately (rather than being left invisible
    // for the full RETRY_VISIBILITY_TIMEOUT_SECONDS), so it doesn't appear to vanish from the DLQ for other callers
    // until that timeout lapses. Scanning stops as soon as every requested id has been found, rather than always
    // scanning the whole DLQ.
    scanDlqMessages(scanLimit = messageCount, visibilityTimeoutSeconds = RETRY_VISIBILITY_TIMEOUT_SECONDS) { msg ->
      if (remainingIds.remove(msg.messageId())) {
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
      } else {
        sqsDlqClient.changeMessageVisibility(
          ChangeMessageVisibilityRequest.builder()
            .queueUrl(dlqUrl)
            .receiptHandle(msg.receiptHandle())
            .visibilityTimeout(0)
            .build(),
        ).await()
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

/** @param approximateReceiveCount SQS's ApproximateReceiveCount for this message - incremented every time it is received without being deleted, including by get-dlq-messages/search-dlq-messages themselves while scanning. It is not exclusively a count of failed deliveries on the main queue, but a higher value still broadly indicates a message that has been retried/inspected repeatedly. Not always available, hence nullable. */
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
