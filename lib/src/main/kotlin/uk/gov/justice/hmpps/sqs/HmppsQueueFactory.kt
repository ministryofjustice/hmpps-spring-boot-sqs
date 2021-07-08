package uk.gov.justice.hmpps.sqs

import com.amazon.sqs.javamessaging.ProviderConfiguration
import com.amazon.sqs.javamessaging.SQSConnectionFactory
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.CreateQueueRequest
import com.amazonaws.services.sqs.model.QueueAttributeName
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jms.config.DefaultJmsListenerContainerFactory
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig
import javax.jms.Session

class HmppsQueueFactory(
  private val context: ConfigurableApplicationContext,
  private val amazonSqsFactory: AmazonSqsFactory,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createHmppsQueues(hmppsSqsProperties: HmppsSqsProperties) =
    hmppsSqsProperties.queues
      .map { (queueId, queueConfig) ->
        val sqsDlqClient = getOrDefaultSqsDlqClient(queueId, queueConfig, hmppsSqsProperties)
        val sqsClient = getOrDefaultSqsClient(queueId, queueConfig, hmppsSqsProperties, sqsDlqClient)
        HmppsQueue(queueId, sqsClient, queueConfig.queueName, sqsDlqClient, queueConfig.dlqName)
          .also { getOrDefaultHealthIndicator(it) }
          .also { createJmsListenerContainerFactory(it, hmppsSqsProperties) }
      }.toList()

  private fun getOrDefaultSqsDlqClient(queueId: String, queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties): AmazonSQS =
    getOrDefaultBean("$queueId-sqs-dlq-client") {
      createSqsDlqClient(queueConfig, hmppsSqsProperties)
    }

  private fun getOrDefaultSqsClient(queueId: String, queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties, sqsDlqClient: AmazonSQS): AmazonSQS =
    getOrDefaultBean("$queueId-sqs-client") {
      createSqsClient(queueConfig, hmppsSqsProperties, sqsDlqClient)
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

  fun createSqsDlqClient(queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties): AmazonSQS =
    with(hmppsSqsProperties) {
      when (provider) {
        "aws" -> amazonSqsFactory.awsSqsDlqClient(queueConfig.dlqName, queueConfig.dlqAccessKeyId, queueConfig.dlqSecretAccessKey, region, queueConfig.asyncDlqClient)
        "localstack" ->
          amazonSqsFactory.localStackSqsDlqClient(queueConfig.dlqName, localstackUrl, region, queueConfig.asyncDlqClient)
            .also { sqsDlqClient -> sqsDlqClient.createQueue(queueConfig.dlqName) }
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }

  fun createSqsClient(queueConfig: QueueConfig, hmppsSqsProperties: HmppsSqsProperties, sqsDlqClient: AmazonSQS) =
    with(hmppsSqsProperties) {
      when (provider) {
        "aws" -> amazonSqsFactory.awsSqsClient(queueConfig.queueName, queueConfig.queueAccessKeyId, queueConfig.queueSecretAccessKey, region, queueConfig.asyncQueueClient)
        "localstack" ->
          amazonSqsFactory.localStackSqsClient(queueConfig.queueName, localstackUrl, region, queueConfig.asyncQueueClient)
            .also { sqsClient -> createLocalStackQueue(sqsClient, sqsDlqClient, queueConfig.queueName, queueConfig.dlqName) }
        // TODO for localstack we should subscribe queues to topics if configured
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }

  private fun createLocalStackQueue(
    sqsClient: AmazonSQS,
    sqsDlqClient: AmazonSQS,
    queueName: String,
    dlqName: String,
  ) =
    sqsDlqClient.getQueueUrl(dlqName).queueUrl
      .let { dlqQueueUrl -> sqsDlqClient.getQueueAttributes(dlqQueueUrl, listOf(QueueAttributeName.QueueArn.toString())).attributes["QueueArn"]!! }
      .also { queueArn ->
        sqsClient.createQueue(
          CreateQueueRequest(queueName).withAttributes(
            mapOf(
              QueueAttributeName.RedrivePolicy.toString() to
                """{"deadLetterTargetArn":"$queueArn","maxReceiveCount":"5"}"""
            )
          )
        )
      }

  fun createJmsListenerContainerFactory(awsSqsClient: AmazonSQS, hmppsSqsProperties: HmppsSqsProperties): DefaultJmsListenerContainerFactory =
    DefaultJmsListenerContainerFactory().apply {
      setConnectionFactory(SQSConnectionFactory(ProviderConfiguration(), awsSqsClient))
      setDestinationResolver(HmppsQueueDestinationResolver(hmppsSqsProperties))
      setConcurrency("1-1")
      setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE)
      setErrorHandler { t: Throwable? -> log.error("Error caught in jms listener", t) }
    }
}
