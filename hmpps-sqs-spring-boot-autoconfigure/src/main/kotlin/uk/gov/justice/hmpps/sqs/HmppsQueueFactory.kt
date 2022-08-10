package uk.gov.justice.hmpps.sqs

import com.amazon.sqs.javamessaging.ProviderConfiguration
import com.amazon.sqs.javamessaging.SQSConnectionFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jms.config.DefaultJmsListenerContainerFactory
import software.amazon.awssdk.services.sns.model.SubscribeRequest
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig
import javax.jms.Session

class HmppsQueueFactory(
  private val context: ConfigurableApplicationContext,
  private val sqsClientFactory: SqsClientFactory,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createHmppsQueues(hmppsSqsProperties: HmppsSqsProperties, hmppsTopics: List<HmppsTopic> = listOf()) =
    hmppsSqsProperties.queues
      .filter { (_, queueConfig) -> !queueConfig.asyncQueueClient }
      .map { (queueId, queueConfig) ->
        val sqsDlqClient = getOrDefaultSqsDlqClient(queueId, queueConfig, hmppsSqsProperties)
        val sqsClient = getOrDefaultSqsClient(queueId, queueConfig, hmppsSqsProperties, sqsDlqClient)
          .also { subscribeToLocalStackTopic(hmppsSqsProperties, queueConfig, hmppsTopics) }
        HmppsQueue(queueId, sqsClient, queueConfig.queueName, sqsDlqClient, queueConfig.dlqName.ifEmpty { null })
          .also { getOrDefaultHealthIndicator(it) }
          .also { createJmsListenerContainerFactory(it, hmppsSqsProperties) }
      }.toList()

  private fun getOrDefaultSqsDlqClient(queueId: String, queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties): SqsClient? =
    if (queueConfig.dlqName.isNotEmpty()) {
      getOrDefaultBean("$queueId-sqs-dlq-client") {
        createSqsDlqClient(queueConfig, hmppsSqsProperties)
          .also { log.info("Created ${hmppsSqsProperties.provider} SqsClient for DLQ queueId $queueId with name ${queueConfig.dlqName}") }
      }
    } else null

  private fun getOrDefaultSqsClient(queueId: String, queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties, sqsDlqClient: SqsClient?): SqsClient =
    getOrDefaultBean("$queueId-sqs-client") {
      createSqsClient(queueConfig, hmppsSqsProperties, sqsDlqClient)
        .also { log.info("Created ${hmppsSqsProperties.provider} SqsClient for queue queueId $queueId with name ${queueConfig.queueName}") }
    }

  private fun getOrDefaultHealthIndicator(hmppsQueue: HmppsQueue): HealthIndicator =
    getOrDefaultBean("${hmppsQueue.id}-health") {
      HmppsQueueHealth(hmppsQueue)
    }

  private fun createJmsListenerContainerFactory(hmppsQueue: HmppsQueue, hmppsSqsProperties: HmppsSqsProperties): HmppsQueueDestinationContainerFactory =
    getOrDefaultBean("${hmppsQueue.id}-jms-listener-factory") {
      HmppsQueueDestinationContainerFactory(hmppsQueue.id, createJmsListenerContainerFactory(hmppsQueue.sqsClient, hmppsSqsProperties))
    }

  @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
  private inline fun <reified T> getOrDefaultBean(beanName: String, createDefaultBean: () -> T) =
    runCatching { context.beanFactory.getBean(beanName) as T }
      .getOrElse {
        createDefaultBean().also { bean -> context.beanFactory.registerSingleton(beanName, bean) }
      }

  fun createSqsDlqClient(queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties): SqsClient {
    val region = hmppsSqsProperties.region
    if (queueConfig.dlqName.isEmpty()) throw MissingDlqNameException()
    return when (findProvider(hmppsSqsProperties.provider)) {
      Provider.AWS -> sqsClientFactory.awsSqsClient(queueConfig.dlqAccessKeyId, queueConfig.dlqSecretAccessKey, region)
      Provider.LOCALSTACK -> sqsClientFactory.localstackSqsClient(hmppsSqsProperties.localstackUrl, region)
        .also { sqsDlqClient -> sqsDlqClient.createQueue(CreateQueueRequest.builder().queueName(queueConfig.dlqName).build()) }
    }
  }

  fun createSqsClient(queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties, sqsDlqClient: SqsClient?): SqsClient {
    val region = hmppsSqsProperties.region
    return when (findProvider(hmppsSqsProperties.provider)) {
      Provider.AWS -> sqsClientFactory.awsSqsClient(queueConfig.queueAccessKeyId, queueConfig.queueSecretAccessKey, region)
      Provider.LOCALSTACK -> sqsClientFactory.localstackSqsClient(hmppsSqsProperties.localstackUrl, region)
        .also { sqsClient -> createLocalStackQueue(sqsClient, sqsDlqClient, queueConfig.queueName, queueConfig.dlqName) }
    }
  }

  private fun createLocalStackQueue(
    sqsClient: SqsClient,
    sqsDlqClient: SqsClient?,
    queueName: String,
    dlqName: String,
    maxReceiveCount: Int,
  ) {
    if (dlqName.isEmpty() || sqsDlqClient == null) {
      sqsClient.createQueue(CreateQueueRequest.builder().queueName(queueName).build())
    } else {
      val dlqUrl = sqsDlqClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build()).queueUrl()
      val dlqArn = sqsDlqClient.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes()[QueueAttributeName.QUEUE_ARN]
      sqsClient.createQueue(
        CreateQueueRequest.builder().queueName(queueName).attributes(mapOf(QueueAttributeName.REDRIVE_POLICY to """{"deadLetterTargetArn":"$dlqArn","maxReceiveCount":"5"}""")).build()
      )
    }
  }

  private fun subscribeToLocalStackTopic(hmppsSqsProperties: HmppsSqsProperties, queueConfig: QueueConfig, hmppsTopics: List<HmppsTopic>) {
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
            .also { log.info("Queue ${queueConfig.queueName} has subscribed to topic with arn ${topic.arn}") }
        }
    }
  }

  fun createJmsListenerContainerFactory(awsSqsClient: SqsClient, hmppsSqsProperties: HmppsSqsProperties): DefaultJmsListenerContainerFactory =
    DefaultJmsListenerContainerFactory().apply {
      setConnectionFactory(SQSConnectionFactory(ProviderConfiguration(), awsSqsClient))
      setDestinationResolver(HmppsQueueDestinationResolver(hmppsSqsProperties))
      setConcurrency("1-1")
      setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE)
      setErrorHandler { t: Throwable? -> log.error("Error caught in jms listener", t) }
    }
}
