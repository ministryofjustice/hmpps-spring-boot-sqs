package uk.gov.justice.hmpps.sqs

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.AmazonSQSAsync
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AmazonSqsFactory {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun awsSqsClient(queueId: String, queueName: String, accessKeyId: String, secretAccessKey: String, region: String, asyncClient: Boolean = false, useWebToken: Boolean): AmazonSQS =
    when (asyncClient) {
      false -> awsAmazonSQS(accessKeyId, secretAccessKey, region, useWebToken)
      true -> awsAmazonSQSAsync(accessKeyId, secretAccessKey, region, useWebToken)
    }.also { log.info("Created an AWS SQS client for queueId $queueId with name $queueName") }

  fun localStackSqsClient(queueId: String, queueName: String, localstackUrl: String, region: String, asyncClient: Boolean = false): AmazonSQS =
    when (asyncClient) {
      false -> localStackAmazonSQS(localstackUrl, region)
      true -> localStackAmazonSQSAsync(localstackUrl, region)
    }.also { log.info("Created a LocalStack SQS client for queueId $queueId with name $queueName") }

  fun awsSqsDlqClient(queueId: String, dlqName: String, accessKeyId: String, secretAccessKey: String, region: String, asyncClient: Boolean = false, useWebToken: Boolean): AmazonSQS =
    when (asyncClient) {
      false -> awsAmazonSQS(accessKeyId, secretAccessKey, region, useWebToken)
      true -> awsAmazonSQSAsync(accessKeyId, secretAccessKey, region, useWebToken)
    }.also { log.info("Created an AWS SQS DLQ client for queueId $queueId with name $dlqName") }

  fun localStackSqsDlqClient(queueId: String, dlqName: String, localstackUrl: String, region: String, asyncClient: Boolean = false): AmazonSQS =
    when (asyncClient) {
      false -> localStackAmazonSQS(localstackUrl, region)
      true -> localStackAmazonSQSAsync(localstackUrl, region)
    }.also { log.info("Created a LocalStack SQS DLQ client for queueId $queueId with name $dlqName") }

  private fun awsAmazonSQS(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean): AmazonSQS {
    val credentialsProvider = if (useWebToken) {
      DefaultAWSCredentialsProviderChain()
    } else {
      AWSStaticCredentialsProvider(BasicAWSCredentials(accessKeyId, secretAccessKey))
    }
    return AmazonSQSClientBuilder.standard()
      .withCredentials(credentialsProvider)
      .withRegion(region)
      .build()
  }

  private fun localStackAmazonSQS(localstackUrl: String, region: String) =
    AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(localstackUrl, region))
      .withCredentials(AWSStaticCredentialsProvider(AnonymousAWSCredentials()))
      .build()

  private fun awsAmazonSQSAsync(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean): AmazonSQSAsync {
    val credentialsProvider = if (useWebToken) {
      DefaultAWSCredentialsProviderChain()
    } else {
      AWSStaticCredentialsProvider(BasicAWSCredentials(accessKeyId, secretAccessKey))
    }
    return AmazonSQSAsyncClientBuilder.standard()
      .withCredentials(credentialsProvider)
      .withRegion(region)
      .build()
  }

  private fun localStackAmazonSQSAsync(localstackUrl: String, region: String) =
    AmazonSQSAsyncClientBuilder.standard()
      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(localstackUrl, region))
      .withCredentials(AWSStaticCredentialsProvider(AnonymousAWSCredentials()))
      .build()
}
