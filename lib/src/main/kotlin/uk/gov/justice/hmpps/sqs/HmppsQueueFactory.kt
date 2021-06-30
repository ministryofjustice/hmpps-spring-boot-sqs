package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.CreateQueueRequest
import com.amazonaws.services.sqs.model.QueueAttributeName
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.ConfigurableApplicationContext
import uk.gov.justice.hmpps.sqs.HmppsQueueProperties.QueueConfig

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
      }.toList()

  private fun getOrDefaultSqsDlqClient(queueId: String, queueConfig: QueueConfig, hmppsQueueProperties: HmppsQueueProperties): AmazonSQS =
    "$queueId-sqs-dlq-client".let { beanName ->
      runCatching { context.beanFactory.getBean(beanName) as AmazonSQS }
        .getOrElse {
          createSqsDlqClient(queueConfig, hmppsQueueProperties)
            .also { context.beanFactory.registerSingleton(beanName, it) }
        }
    }

  private fun getOrDefaultSqsClient(queueId: String, queueConfig: QueueConfig, hmppsQueueProperties: HmppsQueueProperties, sqsDlqClient: AmazonSQS): AmazonSQS =
    "$queueId-sqs-client".let { beanName ->
      runCatching { context.beanFactory.getBean(beanName) as AmazonSQS }
        .getOrElse {
          createSqsClient(queueConfig, hmppsQueueProperties, sqsDlqClient)
            .also { context.beanFactory.registerSingleton(beanName, it) }
        }
    }

  private fun getOrDefaultHealthIndicator(hmppsQueue: HmppsQueue): HealthIndicator =
    "${hmppsQueue.id}-health".let { beanName ->
      runCatching { context.beanFactory.getBean(beanName) as HealthIndicator }
        .getOrElse {
          HmppsQueueHealth(hmppsQueue)
            .also { context.beanFactory.registerSingleton(beanName, it) }
        }
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
}
