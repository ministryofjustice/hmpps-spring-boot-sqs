package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.CreateQueueRequest
import com.amazonaws.services.sqs.model.QueueAttributeName
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext

class HmppsQueueFactory(
  private val context: ConfigurableApplicationContext,
  private val amazonSqsFactory: AmazonSqsFactory,
) {
  companion object {
    val log = LoggerFactory.getLogger(this::class.java)
  }

  fun registerHmppsQueues(hmppsQueueProperties: HmppsQueueProperties) =
    hmppsQueueProperties.queues.map { (queueId, queueConfig) ->
      val sqsDlqClient = createSqsDlqClient(queueConfig, hmppsQueueProperties)
      val sqsClient = createSqsClient(queueConfig, hmppsQueueProperties, sqsDlqClient)
      registerHmppsQueue(queueId, sqsClient, queueConfig.queueName, sqsDlqClient, queueConfig.dlqName)
    }.toList()

  fun registerHmppsQueue(id: String, sqsClient: AmazonSQS, queueName: String, sqsDlqClient: AmazonSQS, dlqName: String) =
    HmppsQueue(id, sqsClient, queueName, sqsDlqClient, dlqName)
      .also { hmppsQueue ->
        listOf(
          "${hmppsQueue.id}-health" to HmppsQueueHealth(hmppsQueue),
          "${hmppsQueue.id}-sqs-client" to sqsClient,
          "${hmppsQueue.id}-sqs-dlq-client" to sqsDlqClient,
        ).forEach { (beanName, bean) -> context.beanFactory.registerSingleton(beanName, bean) }
      }

  private fun createSqsDlqClient(queueConfig: HmppsQueueProperties.QueueConfig, hmppsQueueProperties: HmppsQueueProperties) =
    with(hmppsQueueProperties) {
      when (provider) {
        "aws" -> awsSqsDlqClient(queueConfig.dlqName, queueConfig.dlqAccessKeyId, queueConfig.dlqSecretAccessKey, region)
        "localstack" -> localStackSqsDlqClient(queueConfig.dlqName, localstackUrl, region)
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }

  private fun createSqsClient(queueConfig: HmppsQueueProperties.QueueConfig, hmppsQueueProperties: HmppsQueueProperties, sqsDlqClient: AmazonSQS) =
    with(hmppsQueueProperties) {
      when (provider) {
        "aws" -> awsSqsClient(queueConfig.queueName, queueConfig.queueAccessKeyId, queueConfig.queueSecretAccessKey, region)
        "localstack" -> localStackSqsClient(queueConfig.queueName, queueConfig.dlqName, localstackUrl, region, sqsDlqClient)
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }

  private fun awsSqsClient(queueName: String, accessKeyId: String, secretAccessKey: String, region: String) =
    amazonSqsFactory.awsAmazonSQS(accessKeyId, secretAccessKey, region)
      .also { log.info("Created an AWS SQS client for queue $queueName") }

  private fun localStackSqsClient(queueName: String, dlqName: String, localstackUrl: String, region: String, sqsDlqClient: AmazonSQS) =
    amazonSqsFactory.localStackAmazonSQS(localstackUrl, region)
      .also { sqsClient -> createQueue(sqsClient, sqsDlqClient, queueName, dlqName) }
      .also { log.info("Created a LocalStack SQS client for queue $queueName") }

  private fun awsSqsDlqClient(dlqName: String, accessKeyId: String, secretAccessKey: String, region: String) =
    amazonSqsFactory.awsAmazonSQS(accessKeyId, secretAccessKey, region)
      .also { log.info("Created an AWS SQS DLQ client for DLQ $dlqName") }

  private fun localStackSqsDlqClient(dlqName: String, localstackUrl: String, region: String) =
    amazonSqsFactory.localStackAmazonSQS(localstackUrl, region)
      .also { sqsDlqClient -> sqsDlqClient.createQueue(dlqName) }
      .also { log.info("Created a LocalStack SQS DLQ client for DLQ $dlqName") }

  private fun createQueue(
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
