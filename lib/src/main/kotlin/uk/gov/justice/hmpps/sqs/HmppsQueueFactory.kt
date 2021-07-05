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
import uk.gov.justice.hmpps.sqs.HmppsQueueProperties.QueueConfig
import javax.jms.Session

class HmppsQueueFactory(
  private val context: ConfigurableApplicationContext,
  private val amazonSqsFactory: AmazonSqsFactory,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createHmppsQueues(hmppsQueueProperties: HmppsQueueProperties) =
    hmppsQueueProperties.queues
      .map { (queueId, queueConfig) ->
        val sqsDlqClient = getOrDefaultSqsDlqClient(queueId, queueConfig, hmppsQueueProperties)
        val sqsClient = getOrDefaultSqsClient(queueId, queueConfig, hmppsQueueProperties, sqsDlqClient)
        HmppsQueue(queueId, sqsClient, queueConfig.queueName, sqsDlqClient, queueConfig.dlqName)
          .also { getOrDefaultHealthIndicator(it) }
          .also { createJmsListenerContainerFactory(it, hmppsQueueProperties) }
      }.toList()

  private fun getOrDefaultSqsDlqClient(queueId: String, queueConfig: QueueConfig, hmppsQueueProperties: HmppsQueueProperties): AmazonSQS =
    getOrDefaultBean("$queueId-sqs-dlq-client") {
      createSqsDlqClient(queueConfig, hmppsQueueProperties)
    }

  private fun getOrDefaultSqsClient(queueId: String, queueConfig: QueueConfig, hmppsQueueProperties: HmppsQueueProperties, sqsDlqClient: AmazonSQS): AmazonSQS =
    getOrDefaultBean("$queueId-sqs-client") {
      createSqsClient(queueConfig, hmppsQueueProperties, sqsDlqClient)
    }

  private fun getOrDefaultHealthIndicator(hmppsQueue: HmppsQueue): HealthIndicator =
    getOrDefaultBean("${hmppsQueue.id}-health") {
      HmppsQueueHealth(hmppsQueue)
    }

  private fun createJmsListenerContainerFactory(hmppsQueue: HmppsQueue, hmppsQueueProperties: HmppsQueueProperties): HmppsQueueJmsListenerContainerFactory =
    getOrDefaultBean("${hmppsQueue.id}-jms-listener-factory") {
      HmppsQueueJmsListenerContainerFactory(hmppsQueue.id, createJmsListenerContainerFactory(hmppsQueue.sqsClient, hmppsQueueProperties))
    }

  @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
  private inline fun <reified T> getOrDefaultBean(beanName: String, createDefaultBean: () -> T) =
    runCatching { context.beanFactory.getBean(beanName) as T }
      .getOrElse {
        createDefaultBean().also { bean -> context.beanFactory.registerSingleton(beanName, bean) }
      }

  private fun createSqsDlqClient(queueConfig: QueueConfig, hmppsQueueProperties: HmppsQueueProperties): AmazonSQS =
    with(hmppsQueueProperties) {
      when (provider) {
        "aws" -> awsSqsDlqClient(queueConfig.dlqName, queueConfig.dlqAccessKeyId, queueConfig.dlqSecretAccessKey, region, queueConfig.asyncDlqClient)
        "localstack" -> localStackSqsDlqClient(queueConfig.dlqName, localstackUrl, region, queueConfig.asyncDlqClient)
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }

  private fun createSqsClient(queueConfig: QueueConfig, hmppsQueueProperties: HmppsQueueProperties, sqsDlqClient: AmazonSQS) =
    with(hmppsQueueProperties) {
      when (provider) {
        "aws" -> awsSqsClient(queueConfig.queueName, queueConfig.queueAccessKeyId, queueConfig.queueSecretAccessKey, region, queueConfig.asyncQueueClient)
        "localstack" -> localStackSqsClient(queueConfig.queueName, queueConfig.dlqName, localstackUrl, region, sqsDlqClient, queueConfig.asyncQueueClient)
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }

  fun awsSqsClient(queueName: String, accessKeyId: String, secretAccessKey: String, region: String, asyncClient: Boolean = false): AmazonSQS =
    when (asyncClient) {
      false -> amazonSqsFactory.awsAmazonSQS(accessKeyId, secretAccessKey, region)
      true -> amazonSqsFactory.awsAmazonSQSAsync(accessKeyId, secretAccessKey, region)
    }.also { log.info("Created an AWS SQS client for queue $queueName") }

  fun localStackSqsClient(queueName: String, dlqName: String, localstackUrl: String, region: String, sqsDlqClient: AmazonSQS, asyncClient: Boolean = false): AmazonSQS =
    when (asyncClient) {
      false -> amazonSqsFactory.localStackAmazonSQS(localstackUrl, region)
      true -> amazonSqsFactory.localStackAmazonSQSAsync(localstackUrl, region)
    }
      .also { sqsClient -> createLocalStackQueue(sqsClient, sqsDlqClient, queueName, dlqName) }
      .also { log.info("Created a LocalStack SQS client for queue $queueName") }

  fun awsSqsDlqClient(dlqName: String, accessKeyId: String, secretAccessKey: String, region: String, asyncClient: Boolean = false): AmazonSQS =
    when (asyncClient) {
      false -> amazonSqsFactory.awsAmazonSQS(accessKeyId, secretAccessKey, region)
      true -> amazonSqsFactory.awsAmazonSQSAsync(accessKeyId, secretAccessKey, region)
    }
      .also { log.info("Created an AWS SQS DLQ client for DLQ $dlqName") }

  fun localStackSqsDlqClient(dlqName: String, localstackUrl: String, region: String, asyncClient: Boolean = false): AmazonSQS =
    when (asyncClient) {
      false -> amazonSqsFactory.localStackAmazonSQS(localstackUrl, region)
      true -> amazonSqsFactory.localStackAmazonSQSAsync(localstackUrl, region)
    }
      .also { sqsDlqClient -> sqsDlqClient.createQueue(dlqName) }
      .also { log.info("Created a LocalStack SQS DLQ client for DLQ $dlqName") }

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

  fun createJmsListenerContainerFactory(awsSqsClient: AmazonSQS, hmppsQueueProperties: HmppsQueueProperties): DefaultJmsListenerContainerFactory =
    DefaultJmsListenerContainerFactory().apply {
      setConnectionFactory(SQSConnectionFactory(ProviderConfiguration(), awsSqsClient))
      setDestinationResolver(HmppsQueueDestinationResolver(hmppsQueueProperties))
      setConcurrency("1-1")
      setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE)
      setErrorHandler { t: Throwable? -> log.error("Error caught in jms listener", t) }
    }
}
