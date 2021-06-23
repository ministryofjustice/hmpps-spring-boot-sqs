package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import com.amazonaws.services.sqs.model.CreateQueueRequest
import com.amazonaws.services.sqs.model.QueueAttributeName
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uk.gov.justice.hmpps.sqs.HmppsQueueService

@ConstructorBinding
@ConfigurationProperties(prefix = "hmpps.sqs")
data class SqsConfigProperties(
  val localstackEndpoint: String,
  val region: String,
  val queues: Map<String, QueueConfig>,
) {
  data class QueueConfig(
    val topicName: String = "",
    val queueName: String,
    val queueAccessKeyId: String = "",
    val queueSecretAccessKey: String = "",
    val dlqName: String,
    val dlqAccessKeyId: String = "",
    val dlqSecretAccessKey: String = "",
  )
}

fun SqsConfigProperties.mainQueue() =
  queues["main"] ?: throw MissingQueueException("main queue has not been loaded from configuration properties")

class MissingQueueException(message: String) : RuntimeException(message)

@Configuration
class SqsConfig() {

  companion object {
    val logger = LoggerFactory.getLogger(this::class.java)
  }

  @Bean
  fun sqsClient(sqsConfigProperties: SqsConfigProperties, dlqSqsClient: AmazonSQS, hmppsQueueService: HmppsQueueService): AmazonSQS =
    with(sqsConfigProperties) {
      amazonSQS(localstackEndpoint, region)
        .also { sqsClient -> createQueue(sqsClient, dlqSqsClient, sqsConfigProperties) }
        .also { hmppsQueueService.registerHmppsQueue("main", it, mainQueue().queueName, dlqSqsClient, mainQueue().dlqName) }
        .also { logger.info("Created sqs client for queue ${mainQueue().queueName}") }
    }

  private fun createQueue(
    queueSqsClient: AmazonSQS,
    dlqSqsClient: AmazonSQS,
    sqsConfigProperties: SqsConfigProperties,
  ) =
    dlqSqsClient.getQueueUrl(sqsConfigProperties.mainQueue().dlqName).queueUrl
      .let { dlqQueueUrl -> dlqSqsClient.getQueueAttributes(dlqQueueUrl, listOf(QueueAttributeName.QueueArn.toString())).attributes["QueueArn"]!! }
      .also { queueArn ->
        queueSqsClient.createQueue(
          CreateQueueRequest(sqsConfigProperties.mainQueue().queueName).withAttributes(
            mapOf(
              QueueAttributeName.RedrivePolicy.toString() to
                """{"deadLetterTargetArn":"$queueArn","maxReceiveCount":"5"}"""
            )
          )
        )
      }

  @Bean
  fun sqsDlqClient(sqsConfigProperties: SqsConfigProperties): AmazonSQS =
    amazonSQS(sqsConfigProperties.localstackEndpoint, sqsConfigProperties.region)
      .also { dlqSqsClient -> dlqSqsClient.createQueue(sqsConfigProperties.mainQueue().dlqName) }
      .also { logger.info("Created dlq sqs client for dlq ${sqsConfigProperties.mainQueue().dlqName}") }

  private fun amazonSQS(serviceEndpoint: String, region: String): AmazonSQS =
    AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(serviceEndpoint, region))
      .withCredentials(AWSStaticCredentialsProvider(AnonymousAWSCredentials()))
      .build()
}
