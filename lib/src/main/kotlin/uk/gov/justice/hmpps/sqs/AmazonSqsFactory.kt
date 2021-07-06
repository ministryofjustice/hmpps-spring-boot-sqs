package uk.gov.justice.hmpps.sqs

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AmazonSqsFactory {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun awsSqsClient(queueName: String, accessKeyId: String, secretAccessKey: String, region: String, asyncClient: Boolean = false): AmazonSQS =
    when (asyncClient) {
      false -> awsAmazonSQS(accessKeyId, secretAccessKey, region)
      true -> awsAmazonSQSAsync(accessKeyId, secretAccessKey, region)
    }.also { log.info("Created an AWS SQS client for queue $queueName") }

  fun localStackSqsClient(queueName: String, localstackUrl: String, region: String, asyncClient: Boolean = false): AmazonSQS =
    when (asyncClient) {
      false -> localStackAmazonSQS(localstackUrl, region)
      true -> localStackAmazonSQSAsync(localstackUrl, region)
    }.also { log.info("Created a LocalStack SQS client for queue $queueName") }

  fun awsSqsDlqClient(dlqName: String, accessKeyId: String, secretAccessKey: String, region: String, asyncClient: Boolean = false): AmazonSQS =
    when (asyncClient) {
      false -> awsAmazonSQS(accessKeyId, secretAccessKey, region)
      true -> awsAmazonSQSAsync(accessKeyId, secretAccessKey, region)
    }.also { log.info("Created an AWS SQS DLQ client for DLQ $dlqName") }

  fun localStackSqsDlqClient(dlqName: String, localstackUrl: String, region: String, asyncClient: Boolean = false): AmazonSQS =
    when (asyncClient) {
      false -> localStackAmazonSQS(localstackUrl, region)
      true -> localStackAmazonSQSAsync(localstackUrl, region)
    }.also { log.info("Created a LocalStack SQS DLQ client for DLQ $dlqName") }

  private fun awsAmazonSQS(accessKeyId: String, secretAccessKey: String, region: String) =
    AmazonSQSClientBuilder.standard()
      .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(accessKeyId, secretAccessKey)))
      .withRegion(region)
      .build()

  private fun localStackAmazonSQS(localstackUrl: String, region: String) =
    AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(localstackUrl, region))
      .withCredentials(AWSStaticCredentialsProvider(AnonymousAWSCredentials()))
      .build()

  private fun awsAmazonSQSAsync(accessKeyId: String, secretAccessKey: String, region: String) =
    AmazonSQSAsyncClientBuilder.standard()
      .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(accessKeyId, secretAccessKey)))
      .withRegion(region)
      .build()

  private fun localStackAmazonSQSAsync(localstackUrl: String, region: String) =
    AmazonSQSAsyncClientBuilder.standard()
      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(localstackUrl, region))
      .withCredentials(AWSStaticCredentialsProvider(AnonymousAWSCredentials()))
      .build()
}
