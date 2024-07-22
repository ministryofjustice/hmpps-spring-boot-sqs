package uk.gov.justice.hmpps.sqs

import com.fasterxml.jackson.databind.ObjectMapper
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory
import io.awspring.cloud.sqs.listener.QueueMessageVisibility
import io.awspring.cloud.sqs.listener.errorhandler.ErrorHandler
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sns.model.SubscribeRequest
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig
import uk.gov.justice.hmpps.sqs.telemetry.TraceExtractingMessageInterceptor

class HmppsQueueFactory(
  private val context: ConfigurableApplicationContext,
  private val healthContributorRegistry: HmppsHealthContributorRegistry,
  private val sqsClientFactory: SqsClientFactory,
  private val objectMapper: ObjectMapper,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createHmppsQueues(hmppsSqsProperties: HmppsSqsProperties, hmppsTopics: List<HmppsTopic> = listOf()) =
    hmppsSqsProperties.queues
      .map { (queueId, queueConfig) ->
        val sqsDlqClient = getOrDefaultSqsDlqClient(queueId, queueConfig, hmppsSqsProperties)
        val sqsClient = getOrDefaultSqsClient(queueId, queueConfig, hmppsSqsProperties, sqsDlqClient)
          .also {
            subscribeToLocalStackTopic(it, hmppsSqsProperties, queueConfig, hmppsTopics)
          }
        HmppsQueue(queueId, sqsClient, queueConfig.queueName, sqsDlqClient, queueConfig.dlqName.ifEmpty { null })
          .also { registerHealthIndicator(it) }
          .also { createSqsListenerContainerFactory(it, queueConfig.errorVisibilityTimeout, queueConfig.propagateTracing) }
      }.toList()

  private fun getOrDefaultSqsDlqClient(queueId: String, queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties): SqsAsyncClient? =
    if (queueConfig.dlqName.isNotEmpty()) {
      getOrDefaultBean("$queueId-sqs-dlq-client") {
        createSqsAsyncDlqClient(queueConfig, hmppsSqsProperties)
          .also { log.info("Created ${hmppsSqsProperties.provider} SqsAsyncClient for DLQ queueId $queueId with name ${queueConfig.dlqName}") }
      }
    } else {
      null
    }

  private fun getOrDefaultSqsClient(queueId: String, queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties, sqsDlqClient: SqsAsyncClient?): SqsAsyncClient =
    getOrDefaultBean("$queueId-sqs-client") {
      createSqsAsyncClient(queueConfig, hmppsSqsProperties, sqsDlqClient)
        .also { log.info("Created ${hmppsSqsProperties.provider} SqsAsyncClient for queue queueId $queueId with name ${queueConfig.queueName}") }
    }

  private fun registerHealthIndicator(hmppsQueue: HmppsQueue) =
    healthContributorRegistry.registerContributor("${hmppsQueue.id}-health") {
      HmppsQueueHealth(hmppsQueue)
    }

  @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
  private inline fun <reified T> getOrDefaultBean(beanName: String, createDefaultBean: () -> T) =
    runCatching { context.beanFactory.getBean(beanName) as T }
      .getOrElse {
        createDefaultBean().also { bean -> context.beanFactory.registerSingleton(beanName, bean) }
      }

  private fun createSqsListenerContainerFactory(hmppsQueue: HmppsQueue, errorVisibilityTimeout: Int, propagateTracing: Boolean): HmppsQueueDestinationContainerFactory =
    getOrDefaultBean("${hmppsQueue.id}-sqs-listener-factory") {
      HmppsQueueDestinationContainerFactory(hmppsQueue.id, createSqsListenerContainerFactory(hmppsQueue.sqsClient, errorVisibilityTimeout, propagateTracing))
    }

  private fun createSqsListenerContainerFactory(awsSqsClient: SqsAsyncClient, errorVisibilityTimeout: Int, propagateTracing: Boolean): SqsMessageListenerContainerFactory<Any> =
    SqsMessageListenerContainerFactory
      .builder<Any>()
      .sqsAsyncClient(awsSqsClient)
      .apply {
        if (propagateTracing) {
          messageInterceptor(TraceExtractingMessageInterceptor(objectMapper))
        }
      }
      .errorHandler(
        object : ErrorHandler<Any> {
          override fun handle(message: org.springframework.messaging.Message<Any>, t: Throwable) {
            // SDI-477 remove this logging when we are comfortable that all is working as expected - instant retries
            log.info("Setting visibility of messageId ${message.headers["id"]} to $errorVisibilityTimeout (to initiate faster retry) after receiving exception ${t.cause?.cause?.cause}")
            val sqsVisibility = message.headers["Sqs_VisibilityTimeout"] as QueueMessageVisibility
            sqsVisibility.changeTo(errorVisibilityTimeout)
            throw t
          }
        },
      )
      .build()

  fun createSqsAsyncDlqClient(queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties): SqsAsyncClient {
    val region = hmppsSqsProperties.region
    val provider = findProvider(hmppsSqsProperties.provider)
    if (queueConfig.dlqName.isEmpty()) throw MissingDlqNameException()
    return when (provider) {
      Provider.AWS -> sqsClientFactory.awsSqsAsyncClient(queueConfig.dlqAccessKeyId, queueConfig.dlqSecretAccessKey, region, hmppsSqsProperties.useWebToken, queueConfig.propagateTracing)
      Provider.LOCALSTACK -> {
        sqsClientFactory.localstackSqsAsyncClient(hmppsSqsProperties.localstackUrl, region, queueConfig.propagateTracing)
          .also { sqsDlqClient -> runBlocking { sqsDlqClient.createQueue(CreateQueueRequest.builder().attributes(mapOf(QueueAttributeName.FIFO_QUEUE to queueConfig.fifoQueue, QueueAttributeName.FIFO_THROUGHPUT_LIMIT to queueConfig.fifoThroughputLimit)).queueName(queueConfig.dlqName).build()).await() } }
      }
    }
  }

  fun createSqsAsyncClient(queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties, sqsDlqClient: SqsAsyncClient?): SqsAsyncClient {
    val region = hmppsSqsProperties.region
    return when (findProvider(hmppsSqsProperties.provider)) {
      Provider.AWS -> sqsClientFactory.awsSqsAsyncClient(queueConfig.queueAccessKeyId, queueConfig.queueSecretAccessKey, region, hmppsSqsProperties.useWebToken, queueConfig.propagateTracing)
      Provider.LOCALSTACK -> {
        sqsClientFactory.localstackSqsAsyncClient(hmppsSqsProperties.localstackUrl, region, queueConfig.propagateTracing)
          .also { sqsClient ->
            runBlocking {
              createLocalStackQueue(
                sqsClient = sqsClient,
                sqsDlqClient = sqsDlqClient,
                queueName = queueConfig.queueName,
                dlqName = queueConfig.dlqName,
                maxReceiveCount = queueConfig.dlqMaxReceiveCount,
                visibilityTimeout = queueConfig.visibilityTimeout,
                fifoQueue = queueConfig.fifoQueue,
                fifoThroughputLimit = queueConfig.fifoThroughputLimit,
              )
            }
          }
      }
    }
  }

  private fun createLocalStackQueue(
    sqsClient: SqsAsyncClient,
    sqsDlqClient: SqsAsyncClient?,
    queueName: String,
    dlqName: String,
    maxReceiveCount: Int,
    visibilityTimeout: Int,
    fifoQueue: String,
    fifoThroughputLimit: String?,
  ) = runBlocking {
    if (dlqName.isEmpty() || sqsDlqClient == null) {
      sqsClient.createQueue(CreateQueueRequest.builder().queueName(queueName).attributes(mapOf(QueueAttributeName.FIFO_QUEUE to fifoQueue, QueueAttributeName.FIFO_THROUGHPUT_LIMIT to fifoThroughputLimit)).build()).await()
    } else {
      val dlqUrl = sqsDlqClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build())
      val dlqArn = sqsDlqClient.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlqUrl.await().queueUrl()).attributeNames(QueueAttributeName.QUEUE_ARN).build()).await().attributes()[QueueAttributeName.QUEUE_ARN]
      sqsClient.createQueue(
        CreateQueueRequest.builder().queueName(queueName).attributes(
          mapOf(
            QueueAttributeName.FIFO_QUEUE to fifoQueue,
            QueueAttributeName.FIFO_THROUGHPUT_LIMIT to fifoThroughputLimit,
            QueueAttributeName.REDRIVE_POLICY to """{"deadLetterTargetArn":"$dlqArn","maxReceiveCount":"$maxReceiveCount"}""",
            QueueAttributeName.VISIBILITY_TIMEOUT to "$visibilityTimeout",
          ),
        ).build(),
      ).await()
    }
  }

  private fun subscribeToLocalStackTopic(sqsClient: SqsAsyncClient, hmppsSqsProperties: HmppsSqsProperties, queueConfig: QueueConfig, hmppsTopics: List<HmppsTopic>) = runBlocking {
    if (findProvider(hmppsSqsProperties.provider) == Provider.LOCALSTACK) {
      val queueArn = sqsClient.getQueueAttributes(
        GetQueueAttributesRequest.builder()
          .queueUrl("${hmppsSqsProperties.localstackUrl}/000000000000/${queueConfig.queueName}")
          .attributeNames(QueueAttributeName.QUEUE_ARN).build(),
      ).await().attributes()[QueueAttributeName.QUEUE_ARN]
      val topic = hmppsTopics.firstOrNull { topic -> topic.id == queueConfig.subscribeTopicId }
      if (topic != null && queueArn != null) {
        topic.snsClient
          .subscribe(subscribeRequest(queueConfig, queueArn, topic.arn))
          ?.also { log.info("Queue ${queueConfig.queueName} has subscribed to topic with arn ${topic.arn}") }
          ?.await()
      }
    }
  }

  private fun subscribeRequest(queueConfig: QueueConfig, queueArn: String, topicArn: String): SubscribeRequest {
    val subscribeAttribute = if (queueConfig.subscribeFilter.isEmpty()) mapOf() else mapOf("FilterPolicy" to queueConfig.subscribeFilter)
    return SubscribeRequest.builder()
      .topicArn(topicArn)
      .protocol("sqs")
      .endpoint(queueArn)
      .attributes(subscribeAttribute)
      .build()
  }
}
