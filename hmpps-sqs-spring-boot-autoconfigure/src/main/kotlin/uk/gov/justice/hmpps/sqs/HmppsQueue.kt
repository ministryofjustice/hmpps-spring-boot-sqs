package uk.gov.justice.hmpps.sqs

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.retry.RetryPolicy
import org.springframework.retry.backoff.BackOffPolicy
import org.springframework.retry.support.RetryTemplate
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import uk.gov.justice.hmpps.sqs.HmppsQueue.Companion.log

class HmppsQueue(
  val id: String,
  val sqsClient: SqsAsyncClient,
  val queueName: String,
  val sqsDlqClient: SqsAsyncClient? = null,
  val dlqName: String? = null,
) {
  val queueUrl: String by lazy {
    sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).get().queueUrl()
  }

  val queueArn: String? by lazy {
    getArn(sqsClient, queueUrl)
  }

  val dlqUrl: String? by lazy {
    sqsDlqClient?.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build())?.get()?.queueUrl()
  }

  val dlqArn: String? by lazy {
    sqsDlqClient?.let { c ->
      dlqUrl?.let { url -> getArn(c, url) }
    }
  }

  val maxReceiveCount: Int? by lazy {
    if (dlqName != null) {
      runCatching {
        getMaxReceiveCount(sqsClient, queueUrl)
      }
        .onFailure { log.error("Unable to retrieve maxReceiveCount for queue $queueName", it) }
        .getOrNull()
    } else {
      null
    }
  }

  private fun getArn(client: SqsAsyncClient, url: String) = client.getQueueAttributes(getArnAttribute(url)).get().attributes()[QueueAttributeName.QUEUE_ARN]

  private fun getArnAttribute(url: String) = GetQueueAttributesRequest.builder().queueUrl(url).attributeNames(QueueAttributeName.QUEUE_ARN).build()

  private fun getMaxReceiveCount(client: SqsAsyncClient, url: String) = client.getQueueAttributes(getRedrivePolicyAttribute(url)).get().attributes()[QueueAttributeName.REDRIVE_POLICY]
    .let { gson.fromJson(it, Map::class.java)["maxReceiveCount"].toString().toInt() }

  private fun getRedrivePolicyAttribute(url: String) = GetQueueAttributesRequest.builder().queueUrl(url).attributeNames(QueueAttributeName.REDRIVE_POLICY).build()

  companion object {
    internal val log: Logger = LoggerFactory.getLogger(this::class.java)
    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
  }
}

/**
 * Publishes an event to the specified SQS queue with retry and back off policies.
 *
 * Examples:
 *
 * Scenario where API is called from UI, and we want a reasonable number of retries while the user waits for a response:
 * We can just use the default of 4 attempts that would block the thread for around 7 seconds
 * ```
 * hmppsQueue.sendMessage(
 *         eventType = event.eventType,
 *         event = event.body,
 *       )
 * ```
 *
 * Scenario where retrying is already done by the calling code; for instance an SQS Listener that transforms events that is idempotent so the entire process can be rerun:
 * Given we don't want to tie up the listener thread there is no advantage of retrying on this thread
 *
 * ```
 * hmppsQueue.sendMessage(
 *       eventType = event.eventType,
 *       event = event.body,
 *       retryPolicy = NeverRetryPolicy(),
 *     )
 * ```
 *
 * Scenario where thread can hang for longer with more retries, e.g. where there is no UI waiting such as in a batch job
 * ```
 * hmppsQueue.sendMessage(
 *       eventType = event.eventType,
 *       event = event.body,
 *       retryPolicy = SimpleRetryPolicy().apply { maxAttempts = 10 }
 *     )
 * ```
 *
 * Scenario where we want to retry many times but use no more than 10 seconds
 * ```
 * hmppsQueue.sendMessage(
 *       eventType = event.eventType,
 *       event = event.body,
 *       retryPolicy = SimpleRetryPolicy().apply { maxAttempts = 20 },
 *       backoffPolicy = FixedBackOffPolicy().apply { backOffPeriod = 500 },
 *     )
 * ```
 *
 * Scenario where we need to set more SQS attributes
 * ```
 * hmppsQueue.sendMessage(
 *       eventType = event.eventType,
 *       event = event.body,
 *       attributes = mapOf(
 *         "fruit" to MessageAttributeValue.builder().dataType("String").stringValue("banana").build(),
 *       ),
 *     )
 * ```
 *
 * Scenario where we need to delay the sending of the message, possibly to avoid a race condition such as where the
 * receiving client would otherwise be in danger of prematurely getting stale data
 * ```
 * hmppsQueue.sendMessage(
 *       eventType = event.eventType,
 *       event = event.body,
 *       delayInSeconds = 5,
 *     )
 * ```
 *
 * @param eventType The type of the event, which clients listen to, e.g., prisoner.movement.added.
 * @param event The event data in JSON format as a string.
 * @param delayInSeconds How long to delay the queueing of the message, if required.
 * @param noTracing Whether to prevent distributed tracing of this message.  This adds noTracing as a message attribute.
 * @param attributes A map of additional message attributes. eventType is always added as a message attribute, so only supply this if you need additional attributes.
 * @param retryPolicy The policy for retrying the send request in case of failure. By default, this will retry 3 times after an error.
 * @param backOffPolicy The policy for backing off between retries. By default, this will be an exponential policy starting at 1 second with a multiplier of 2
 * @return A response from the send request containing details such as the message ID.
 */
fun HmppsQueue.sendMessage(
  eventType: String,
  event: String,
  delayInSeconds: Int? = null,
  noTracing: Boolean = false,
  attributes: Map<String, MessageAttributeValue> = emptyMap(),
  retryPolicy: RetryPolicy = DEFAULT_RETRY_POLICY,
  backOffPolicy: BackOffPolicy = DEFAULT_BACKOFF_POLICY,
): SendMessageResponse {
  val retryTemplate = RetryTemplate().apply {
    setRetryPolicy(retryPolicy)
    setBackOffPolicy(backOffPolicy)
  }
  val sendMessageRequest = SendMessageRequest.builder()
    .queueUrl(queueUrl)
    .messageBody(event)
    .messageAttributes(eventTypeSqsMap(eventType, noTracing) + attributes)
    .apply { delayInSeconds?.also { delaySeconds(it) } }
    .build()

  return runCatching {
    retryTemplate.execute<SendMessageResponse, Exception> { sqsClient.sendMessage(sendMessageRequest).get() }
  }.onFailure {
    log.error("""Unable to send message {} with body "{}"""", eventType, event)
  }.getOrThrow()
}
