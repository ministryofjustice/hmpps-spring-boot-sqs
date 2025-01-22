package uk.gov.justice.hmpps.sqs

import org.slf4j.LoggerFactory
import org.springframework.retry.RetryPolicy
import org.springframework.retry.backoff.BackOffPolicy
import org.springframework.retry.backoff.ExponentialBackOffPolicy
import org.springframework.retry.policy.SimpleRetryPolicy
import org.springframework.retry.support.RetryTemplate
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.sns.AmazonSNSExtendedAsyncClient
import software.amazon.sns.SNSExtendedAsyncClientConfiguration

class HmppsTopic(
  val id: String,
  val arn: String,
  val snsClient: SnsAsyncClient,
)

val DEFAULT_RETRY_POLICY: RetryPolicy = SimpleRetryPolicy().apply { maxAttempts = 4 }
val DEFAULT_BACKOFF_POLICY: BackOffPolicy = ExponentialBackOffPolicy().apply { initialInterval = 1000L }
private val log = LoggerFactory.getLogger(HmppsTopic::class.java)

/**
 * Publishes an event to the specified SNS topic with retry and back off policies.
 *
 * Examples:
 *
 * Scenario where API is called from UI, and we want a reasonable number of retries while the user waits for a response:
 * We can just use the default of 4 attempts that would block the thread for around 7 seconds
 * ```
 * hmppsDomainTopic.publish(
 *         eventType = event.eventType,
 *         event = event.body,
 *       )
 * ```
 *
 * Scenario where retrying is already done by the calling code; for instance an SQS Listener that transforms events that is idempotent so the entire process can be rerun:
 * Given we don't want to tie up the listener thread there is no advantage of retrying on this thread so
 *
 * ```
 *    hmppsDomainTopic.publish(
 *       eventType = event.eventType,
 *       event = event.body,
 *       retryPolicy = NeverRetryPolicy(),
 *     )
 * ```
 *
 *
 * Scenario where thread can hang for longer with more retries
 * ```
 * hmppsDomainTopic.publish(
 *         eventType = event.eventType,
 *         event = event.body,
 *         retryPolicy = SimpleRetryPolicy().apply { maxAttempts = 10 }
 *       )
 * ```
 *
 * Scenario where we want to retry many times but use no more than 10 seconds
 * ```
 * hmppsDomainTopic.publish(
 *         eventType = event.eventType,
 *         event = event.body,
 *         retryPolicy = SimpleRetryPolicy().apply { maxAttempts = 20 },
 *         backoffPolicy = FixedBackOffPolicy().apply { backOffPeriod = 500 },
 *       )
 * ```
 *
 * Scenario where we need to set more SNS attributes
 * ```
 * hmppsDomainTopic.publish(
 *         eventType = event.eventType,
 *         event = event.body,
 *         attributes = mapOf(
 *           "fruit" to MessageAttributeValue.builder().dataType("String").stringValue("banana").build(),
 *         ),
 *        )
 * ```
 *
 * @param eventType The type of the event, which clients listen to, e.g., prisoner.movement.added.
 * @param event The event data in JSON format as a string.
 * @oaram noTracing Whether to prevent distributed tracing of this message.  This adds noTracing as a message attribute.
 * @param attributes A map of additional message attributes. eventType is always added as a message attribute, so only supply this if you need additional attributes.
 * @param retryPolicy The policy for retrying the publish request in case of failure. By default, this will retry 3 times after an error.
 * @param backOffPolicy The policy for backing off between retries. By default, this will be an exponential policy starting at 1 second with a multiplier of 2
 * @return A response from the publish request containing details such as the message ID.
 */
fun HmppsTopic.publish(
  eventType: String,
  event: String,
  noTracing: Boolean = false,
  attributes: Map<String, MessageAttributeValue> = emptyMap(),
  retryPolicy: RetryPolicy = DEFAULT_RETRY_POLICY,
  backOffPolicy: BackOffPolicy = DEFAULT_BACKOFF_POLICY,
  messageGroupId: String? = null,
): PublishResponse {
  val retryTemplate = retryTemplate(retryPolicy, backOffPolicy)
  val publishRequestBuilder = PublishRequest.builder().topicArn(arn).message(event).messageAttributes(
    eventTypeSnsMap(eventType, noTracing) + attributes,
  )
  messageGroupId?.let { publishRequestBuilder.messageGroupId(it) }
  val publishRequest = publishRequestBuilder.build()

  return runCatching {
    retryTemplate.execute<PublishResponse, Exception> { snsClient.publish(publishRequest).get() }
  }.onFailure {
    log.error("""Unable to publish {} with body "{}"""", eventType, event)
  }.getOrThrow()
}

fun HmppsTopic.publishLargeMessage(
  eventType: String,
  event: String,
  noTracing: Boolean = false,
  attributes: Map<String, MessageAttributeValue> = emptyMap(),
  retryPolicy: RetryPolicy = DEFAULT_RETRY_POLICY,
  backOffPolicy: BackOffPolicy = DEFAULT_BACKOFF_POLICY,
  messageGroupId: String? = null,
  amazonS3AsyncClient: S3AsyncClient,
  s3BucketName: String,
): PublishResponse {
  val retryTemplate = retryTemplate(retryPolicy, backOffPolicy)
  val publishRequestBuilder = PublishRequest.builder().topicArn(arn).messageAttributes(
    eventTypeSnsMap(eventType, noTracing) + attributes,
  ).message(event)
  messageGroupId?.let { publishRequestBuilder.messageGroupId(it) }

  val publishRequest = publishRequestBuilder.build()

  val snsExtendedAsyncClientConfiguration: SNSExtendedAsyncClientConfiguration = SNSExtendedAsyncClientConfiguration()
    .withAlwaysThroughS3(true)
    .withPayloadSupportEnabled(amazonS3AsyncClient, s3BucketName)

  val sns = AmazonSNSExtendedAsyncClient(
    snsClient,
    snsExtendedAsyncClientConfiguration,
  )

  return runCatching {
    retryTemplate.execute<PublishResponse, Exception> { sns.publish(publishRequest, ).get() }
  }.onFailure {
    log.error("""Unable to publish large message {} with body "{}"""", eventType, event)
  }.getOrThrow()
}

private fun retryTemplate(
  retryPolicy: RetryPolicy,
  backOffPolicy: BackOffPolicy,
): RetryTemplate {
  val retryTemplate = RetryTemplate().apply {
    setRetryPolicy(retryPolicy)
    setBackOffPolicy(backOffPolicy)
  }
  return retryTemplate
}
