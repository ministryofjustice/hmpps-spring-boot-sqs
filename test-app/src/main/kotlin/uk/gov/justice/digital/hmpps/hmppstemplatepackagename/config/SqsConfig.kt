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

@ConstructorBinding
@ConfigurationProperties(prefix = "hmpps.sqs")
data class SqsConfigProperties(
  val localstackEndpoint: String,
  val region: String,
  val dlqName: String,
  val queueName: String,
)

@Configuration
class SqsConfig {

  companion object {
    val logger = LoggerFactory.getLogger(this::class.java)
  }

  @Bean
  fun sqsClient(sqsConfigProperties: SqsConfigProperties, dlqSqsClient: AmazonSQS): AmazonSQS =
    amazonSQS(sqsConfigProperties.localstackEndpoint, sqsConfigProperties.region)
      .also { sqsClient -> createQueue(sqsClient, dlqSqsClient, sqsConfigProperties) }
      .also { logger.info("Created sqs client for queue ${sqsConfigProperties.queueName}") }

  private fun createQueue(
    queueSqsClient: AmazonSQS,
    dlqSqsClient: AmazonSQS,
    sqsConfigProperties: SqsConfigProperties,
  ) =
    dlqSqsClient.getQueueUrl(sqsConfigProperties.dlqName).queueUrl
      .let { dlqQueueUrl -> dlqSqsClient.getQueueAttributes(dlqQueueUrl, listOf(QueueAttributeName.QueueArn.toString())).attributes["QueueArn"]!! }
      .run {
        queueSqsClient.createQueue(
          CreateQueueRequest(sqsConfigProperties.queueName).withAttributes(
            mapOf(
              QueueAttributeName.RedrivePolicy.toString() to
                """{"deadLetterTargetArn":"$this","maxReceiveCount":"5"}"""
            )
          )
        )
      }

  @Bean
  fun sqsDlqClient(sqsConfigProperties: SqsConfigProperties): AmazonSQS =
    amazonSQS(sqsConfigProperties.localstackEndpoint, sqsConfigProperties.region)
      .also { dlqSqsClient -> dlqSqsClient.createQueue(sqsConfigProperties.dlqName) }
      .also { logger.info("Created dlq sqs client for dlq ${sqsConfigProperties.dlqName}") }

  private fun amazonSQS(serviceEndpoint: String, region: String): AmazonSQS =
    AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(serviceEndpoint, region))
      .withCredentials(AWSStaticCredentialsProvider(AnonymousAWSCredentials()))
      .build()
}
