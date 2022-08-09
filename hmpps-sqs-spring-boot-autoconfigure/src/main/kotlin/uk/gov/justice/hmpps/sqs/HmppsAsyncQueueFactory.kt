package uk.gov.justice.hmpps.sqs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sns.model.SubscribeRequest
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig

class HmppsAsyncQueueFactory(
  private val context: ConfigurableApplicationContext,
  private val sqsAsyncClientFactory: SqsAsyncClientFactory,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createHmppsAsyncQueues(hmppsSqsProperties: HmppsSqsProperties, hmppsTopics: List<HmppsAsyncTopic> = listOf()) =
    hmppsSqsProperties.queues
      .filter { (_, queueConfig) -> queueConfig.asyncQueueClient }
      .map { (queueId, queueConfig) ->
        val sqsDlqClient = getOrDefaultSqsAsyncDlqClient(queueId, queueConfig, hmppsSqsProperties)
        val sqsClient = getOrDefaultSqsAsyncClient(queueId, queueConfig, hmppsSqsProperties, sqsDlqClient)
          .also { subscribeToLocalStackAsyncTopic(hmppsSqsProperties, queueConfig, hmppsTopics) }
        HmppsAsyncQueue(queueId, sqsClient, queueConfig.queueName, sqsDlqClient, queueConfig.dlqName.ifEmpty { null })
          .also { getOrDefaultAsyncHealthIndicator(it) }
      }.toList()

  private fun getOrDefaultSqsAsyncDlqClient(queueId: String, queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties): SqsAsyncClient? =
    if (queueConfig.dlqName.isNotEmpty()) {
      getOrDefaultBean("$queueId-sqs-dlq-client") {
        createSqsAsyncDlqClient(queueConfig, hmppsSqsProperties)
          .also { log.info("Created ${hmppsSqsProperties.provider} SqsAsyncClient for DLQ queueId $queueId with name ${queueConfig.dlqName}")}
      }
    } else null

  private fun getOrDefaultSqsAsyncClient(queueId: String, queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties, sqsDlqClient: SqsAsyncClient?): SqsAsyncClient =
    getOrDefaultBean("$queueId-sqs-client") {
      createSqsAsyncClient(queueConfig, hmppsSqsProperties, sqsDlqClient)
        .also { log.info("Created ${hmppsSqsProperties.provider} SqsAsyncClient for queue queueId $queueId with name ${queueConfig.queueName}")}
    }

  private fun getOrDefaultAsyncHealthIndicator(hmppsQueue: HmppsAsyncQueue): HealthIndicator =
    getOrDefaultBean("${hmppsQueue.id}-health") {
      HmppsAsyncQueueHealth(hmppsQueue)
    }

  @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
  private inline fun <reified T> getOrDefaultBean(beanName: String, createDefaultBean: () -> T) =
    runCatching { context.beanFactory.getBean(beanName) as T }
      .getOrElse {
        createDefaultBean().also { bean -> context.beanFactory.registerSingleton(beanName, bean) }
      }

  fun createSqsAsyncDlqClient(queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties): SqsAsyncClient {
    val region = hmppsSqsProperties.region
    val provider = findProvider(hmppsSqsProperties.provider)
    if (queueConfig.dlqName.isEmpty()) throw MissingDlqNameException()
    return when (provider) {
      Provider.AWS -> sqsAsyncClientFactory.awsSqsAsyncClient(queueConfig.dlqAccessKeyId, queueConfig.dlqSecretAccessKey, region)
      Provider.LOCALSTACK -> sqsAsyncClientFactory.localstackSqsAsyncClient(hmppsSqsProperties.localstackUrl, region)
        .also { sqsDlqClient -> sqsDlqClient.createQueue(CreateQueueRequest.builder().queueName(queueConfig.dlqName).build()) }
    }
  }

  fun createSqsAsyncClient(queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties, sqsDlqClient: SqsAsyncClient?): SqsAsyncClient {
    val region = hmppsSqsProperties.region
    return when (findProvider(hmppsSqsProperties.provider)) {
      Provider.AWS -> sqsAsyncClientFactory.awsSqsAsyncClient(queueConfig.queueAccessKeyId, queueConfig.queueSecretAccessKey, region)
      Provider.LOCALSTACK -> sqsAsyncClientFactory.localstackSqsAsyncClient(hmppsSqsProperties.localstackUrl, region)
        .also { sqsClient -> createLocalStackQueueAsync(sqsClient, sqsDlqClient, queueConfig.queueName, queueConfig.dlqName) }
    }
  }

  private fun createLocalStackQueueAsync(
    sqsClient: SqsAsyncClient,
    sqsDlqClient: SqsAsyncClient?,
    queueName: String,
    dlqName: String,
  ) {
    if (dlqName.isEmpty() || sqsDlqClient == null) {
      sqsClient.createQueue(CreateQueueRequest.builder().queueName(queueName).build())
    } else {
      sqsDlqClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build())
        .thenApply(GetQueueUrlResponse::queueUrl)
        .whenComplete { dlqUrl, _ ->
          sqsDlqClient.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN).build())
            .thenApply { it.attributes()[QueueAttributeName.QUEUE_ARN] }
        }
        .whenComplete { dlqArn, _ ->
          sqsClient.createQueue(
            CreateQueueRequest.builder().queueName(queueName).attributes(mapOf(QueueAttributeName.REDRIVE_POLICY to """{"deadLetterTargetArn":"$dlqArn","maxReceiveCount":"5"}""")).build()
          )
        }
    }
  }

  private fun subscribeToLocalStackAsyncTopic(hmppsSqsProperties: HmppsSqsProperties, queueConfig: QueueConfig, hmppsTopics: List<HmppsAsyncTopic>) {
    if (findProvider(hmppsSqsProperties.provider) == Provider.LOCALSTACK) {
      hmppsTopics.firstOrNull { topic -> topic.id == queueConfig.subscribeTopicId }
        ?.also { topic ->
          val subscribeAttribute = if (queueConfig.subscribeFilter.isEmpty()) mapOf() else mapOf("FilterPolicy" to queueConfig.subscribeFilter)
          topic.snsClient.subscribe(
            SubscribeRequest.builder()
              .topicArn(topic.arn)
              .protocol("sqs")
              .endpoint("${hmppsSqsProperties.localstackUrl}/queue/${queueConfig.queueName}")
              .attributes(subscribeAttribute)
              .build()
          )
            .whenComplete { _, _ -> log.info("Queue ${queueConfig.queueName} has subscribed to topic with arn ${topic.arn}") } // TODO handle error
        }
    }
  }
}
