package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import com.amazonaws.services.sqs.model.CreateQueueRequest
import com.amazonaws.services.sqs.model.QueueAttributeName
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uk.gov.justice.hmpps.sqs.HmppsQueueProperties
import uk.gov.justice.hmpps.sqs.HmppsQueueService

fun HmppsQueueProperties.mainQueue() =
  queues["mainQueue"] ?: throw MissingQueueException("main queue has not been loaded from configuration properties")

fun HmppsQueueProperties.anotherQueue() =
  queues["anotherQueue"] ?: throw MissingQueueException("another queue has not been loaded from configuration properties")

class MissingQueueException(message: String) : RuntimeException(message)

@Configuration
class SqsConfig() {

  companion object {
    val log = LoggerFactory.getLogger(this::class.java)
  }

  @Bean
  fun sqsClient(hmppsQueueProperties: HmppsQueueProperties, sqsDlqClient: AmazonSQS, hmppsQueueService: HmppsQueueService): AmazonSQS =
    with(hmppsQueueProperties) {
      amazonSQS(localstackUrl, region)
        .also { sqsClient -> createQueue(sqsClient, sqsDlqClient, hmppsQueueProperties.mainQueue()) }
        .also { hmppsQueueService.registerHmppsQueue("mainQueue", it, mainQueue().queueName, sqsDlqClient, mainQueue().dlqName) }
        .also { log.info("Created sqs client for queue ${mainQueue().queueName}") }
    }

  private fun createQueue(
    queueSqsClient: AmazonSQS,
    dlqSqsClient: AmazonSQS,
    hmppsQueueConfig: HmppsQueueProperties.QueueConfig,
  ) =
    dlqSqsClient.getQueueUrl(hmppsQueueConfig.dlqName).queueUrl
      .let { dlqQueueUrl -> dlqSqsClient.getQueueAttributes(dlqQueueUrl, listOf(QueueAttributeName.QueueArn.toString())).attributes["QueueArn"]!! }
      .also { queueArn ->
        queueSqsClient.createQueue(
          CreateQueueRequest(hmppsQueueConfig.queueName).withAttributes(
            mapOf(
              QueueAttributeName.RedrivePolicy.toString() to
                """{"deadLetterTargetArn":"$queueArn","maxReceiveCount":"5"}"""
            )
          )
        )
      }

  @Bean
  fun anotherSqsClient(hmppsQueueProperties: HmppsQueueProperties, anotherSqsDlqClient: AmazonSQS, hmppsQueueService: HmppsQueueService): AmazonSQS =
    with(hmppsQueueProperties) {
      amazonSQS(localstackUrl, region)
        .also { sqsClient -> createQueue(sqsClient, anotherSqsDlqClient, hmppsQueueProperties.anotherQueue()) }
        .also { hmppsQueueService.registerHmppsQueue("anotherQueue", it, anotherQueue().queueName, anotherSqsDlqClient, anotherQueue().dlqName) }
        .also { log.info("Created sqs client for queue ${anotherQueue().queueName}") }
    }

  @Bean
  fun sqsDlqClient(hmppsQueueProperties: HmppsQueueProperties): AmazonSQS =
    amazonSQS(hmppsQueueProperties.localstackUrl, hmppsQueueProperties.region)
      .also { dlqSqsClient -> dlqSqsClient.createQueue(hmppsQueueProperties.mainQueue().dlqName) }
      .also { log.info("Created dlq sqs client for dlq ${hmppsQueueProperties.mainQueue().dlqName}") }

  @Bean
  fun anotherSqsDlqClient(hmppsQueueProperties: HmppsQueueProperties): AmazonSQS =
    amazonSQS(hmppsQueueProperties.localstackUrl, hmppsQueueProperties.region)
      .also { dlqSqsClient -> dlqSqsClient.createQueue(hmppsQueueProperties.anotherQueue().dlqName) }
      .also { log.info("Created dlq sqs client for dlq ${hmppsQueueProperties.anotherQueue().dlqName}") }

  private fun amazonSQS(serviceEndpoint: String, region: String): AmazonSQS =
    AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(serviceEndpoint, region))
      .withCredentials(AWSStaticCredentialsProvider(AnonymousAWSCredentials()))
      .build()
}
