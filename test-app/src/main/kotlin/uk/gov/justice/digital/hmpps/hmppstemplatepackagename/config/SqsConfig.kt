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

class MissingQueueException(message: String) : RuntimeException(message)

@Configuration
class SqsConfig() {

  companion object {
    val logger = LoggerFactory.getLogger(this::class.java)
  }

  @Bean
  fun sqsClient(hmppsQueueProperties: HmppsQueueProperties, dlqSqsClient: AmazonSQS, hmppsQueueService: HmppsQueueService): AmazonSQS =
    with(hmppsQueueProperties) {
      amazonSQS(localstackUrl, region)
        .also { sqsClient -> createQueue(sqsClient, dlqSqsClient, hmppsQueueProperties) }
        .also { hmppsQueueService.registerHmppsQueue("mainQueue", it, mainQueue().queueName, dlqSqsClient, mainQueue().dlqName) }
        .also { logger.info("Created sqs client for queue ${mainQueue().queueName}") }
    }

  private fun createQueue(
    queueSqsClient: AmazonSQS,
    dlqSqsClient: AmazonSQS,
    hmppsQueueProperties: HmppsQueueProperties,
  ) =
    dlqSqsClient.getQueueUrl(hmppsQueueProperties.mainQueue().dlqName).queueUrl
      .let { dlqQueueUrl -> dlqSqsClient.getQueueAttributes(dlqQueueUrl, listOf(QueueAttributeName.QueueArn.toString())).attributes["QueueArn"]!! }
      .also { queueArn ->
        queueSqsClient.createQueue(
          CreateQueueRequest(hmppsQueueProperties.mainQueue().queueName).withAttributes(
            mapOf(
              QueueAttributeName.RedrivePolicy.toString() to
                """{"deadLetterTargetArn":"$queueArn","maxReceiveCount":"5"}"""
            )
          )
        )
      }

  @Bean
  fun sqsDlqClient(hmppsQueueProperties: HmppsQueueProperties): AmazonSQS =
    amazonSQS(hmppsQueueProperties.localstackUrl, hmppsQueueProperties.region)
      .also { dlqSqsClient -> dlqSqsClient.createQueue(hmppsQueueProperties.mainQueue().dlqName) }
      .also { logger.info("Created dlq sqs client for dlq ${hmppsQueueProperties.mainQueue().dlqName}") }

  private fun amazonSQS(serviceEndpoint: String, region: String): AmazonSQS =
    AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(serviceEndpoint, region))
      .withCredentials(AWSStaticCredentialsProvider(AnonymousAWSCredentials()))
      .build()
}
