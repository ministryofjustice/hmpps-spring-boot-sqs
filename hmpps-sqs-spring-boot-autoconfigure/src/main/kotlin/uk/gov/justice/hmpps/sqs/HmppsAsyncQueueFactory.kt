package uk.gov.justice.hmpps.sqs

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sns.model.SubscribeRequest
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig

class HmppsAsyncQueueFactory(
  private val context: ConfigurableApplicationContext,
  private val sqsAsyncClientFactory: SqsAsyncClientFactory,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createHmppsAsyncQueues(hmppsSqsProperties: HmppsSqsProperties, hmppsTopics: List<HmppsTopic> = listOf(), hmppsAsyncTopics: List<HmppsAsyncTopic> = listOf()) =
    hmppsSqsProperties.queues
      .filter { (_, queueConfig) -> queueConfig.asyncQueueClient }
      .map { (queueId, queueConfig) ->
        val sqsDlqClient = getOrDefaultSqsAsyncDlqClient(queueId, queueConfig, hmppsSqsProperties)
        val sqsClient = getOrDefaultSqsAsyncClient(queueId, queueConfig, hmppsSqsProperties, sqsDlqClient)
          .also { subscribeToLocalStackTopic(hmppsSqsProperties, queueConfig, hmppsTopics, hmppsAsyncTopics) }
        HmppsAsyncQueue(queueId, sqsClient, queueConfig.queueName, sqsDlqClient, queueConfig.dlqName.ifEmpty { null })
          .also { getOrDefaultAsyncHealthIndicator(it) }
      }.toList()

  private fun getOrDefaultSqsAsyncDlqClient(queueId: String, queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties): SqsAsyncClient? =
    if (queueConfig.dlqName.isNotEmpty()) {
      getOrDefaultBean("$queueId-sqs-dlq-client") {
        createSqsAsyncDlqClient(queueConfig, hmppsSqsProperties)
          .also { log.info("Created ${hmppsSqsProperties.provider} SqsAsyncClient for DLQ queueId $queueId with name ${queueConfig.dlqName}") }
      }
    } else null

  private fun getOrDefaultSqsAsyncClient(queueId: String, queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties, sqsDlqClient: SqsAsyncClient?): SqsAsyncClient =
    getOrDefaultBean("$queueId-sqs-client") {
      createSqsAsyncClient(queueConfig, hmppsSqsProperties, sqsDlqClient)
        .also { log.info("Created ${hmppsSqsProperties.provider} SqsAsyncClient for queue queueId $queueId with name ${queueConfig.queueName}") }
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
      Provider.LOCALSTACK -> {
        sqsAsyncClientFactory.localstackSqsAsyncClient(hmppsSqsProperties.localstackUrl, region)
          .also { sqsDlqClient -> runBlocking { sqsDlqClient.createQueue(CreateQueueRequest.builder().queueName(queueConfig.dlqName).build()).await() } }
      }
    }
  }

  fun createSqsAsyncClient(queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties, sqsDlqClient: SqsAsyncClient?): SqsAsyncClient {
    val region = hmppsSqsProperties.region
    return when (findProvider(hmppsSqsProperties.provider)) {
      Provider.AWS -> sqsAsyncClientFactory.awsSqsAsyncClient(queueConfig.queueAccessKeyId, queueConfig.queueSecretAccessKey, region)
      Provider.LOCALSTACK -> {
        sqsAsyncClientFactory.localstackSqsAsyncClient(hmppsSqsProperties.localstackUrl, region)
          .also { sqsClient -> runBlocking { createLocalStackQueueAsync(sqsClient, sqsDlqClient, queueConfig.queueName, queueConfig.dlqName, queueConfig.dlqMaxReceiveCount) } }
      }
    }
  }

  private suspend fun createLocalStackQueueAsync(
    sqsAsyncClient: SqsAsyncClient,
    sqsDlqAsyncClient: SqsAsyncClient?,
    queueName: String,
    dlqName: String,
    maxReceiveCount: Int,
  ) {
    if (dlqName.isEmpty() || sqsDlqAsyncClient == null) {
      sqsAsyncClient.createQueue(CreateQueueRequest.builder().queueName(queueName).build()).await()
    } else {
      val dlqUrl = sqsDlqAsyncClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build())
      val dlqArn = sqsDlqAsyncClient.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlqUrl.await().queueUrl()).attributeNames(QueueAttributeName.QUEUE_ARN).build()).await().attributes()[QueueAttributeName.QUEUE_ARN]
      sqsAsyncClient.createQueue(
        CreateQueueRequest.builder().queueName(queueName).attributes(mapOf(QueueAttributeName.REDRIVE_POLICY to """{"deadLetterTargetArn":"$dlqArn","maxReceiveCount":"$maxReceiveCount"}""")).build()
      ).await()
    }
  }

  private fun subscribeToLocalStackTopic(hmppsSqsProperties: HmppsSqsProperties, queueConfig: QueueConfig, hmppsTopics: List<HmppsTopic>, hmppsAsyncTopics: List<HmppsAsyncTopic>) {
    if (findProvider(hmppsSqsProperties.provider) != Provider.LOCALSTACK) {
      return
    }

    val topic = hmppsTopics.firstOrNull { topic -> topic.id == queueConfig.subscribeTopicId }
    if (topic != null) {
      topic.snsClient.subscribe(subscribeRequest(queueConfig, topic.arn, hmppsSqsProperties.localstackUrl))
        .also { HmppsQueueFactory.log.info("Queue ${queueConfig.queueName} has subscribed to topic with arn ${topic.arn}") }
      return
    }

    val asyncTopic = hmppsAsyncTopics.firstOrNull { asyncTopic -> asyncTopic.id == queueConfig.subscribeTopicId }
    if (asyncTopic != null) {
      runBlocking {
        asyncTopic.snsClient.subscribe(subscribeRequest(queueConfig, asyncTopic.arn, hmppsSqsProperties.localstackUrl))
      }.also { HmppsQueueFactory.log.info("Queue ${queueConfig.queueName} has subscribed to async topic with arn ${asyncTopic.arn}") }
      return
    }
  }

  private fun subscribeRequest(queueConfig: QueueConfig, topicArn: String, localstackUrl: String): SubscribeRequest {
    val subscribeAttribute = if (queueConfig.subscribeFilter.isEmpty()) mapOf() else mapOf("FilterPolicy" to queueConfig.subscribeFilter)
    return SubscribeRequest.builder()
      .topicArn(topicArn)
      .protocol("sqs")
      .endpoint("${localstackUrl}/queue/${queueConfig.queueName}")
      .attributes(subscribeAttribute)
      .build()
  }
}
