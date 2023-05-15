package uk.gov.justice.hmpps.sqs

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sns.AmazonSNS
import com.amazonaws.services.sns.AmazonSNSAsync
import com.amazonaws.services.sns.AmazonSNSAsyncClientBuilder
import com.amazonaws.services.sns.AmazonSNSClientBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AmazonSnsFactory {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun awsSnsClient(topicId: String, accessKeyId: String, secretAccessKey: String, region: String, asyncClient: Boolean, useWebToken: Boolean): AmazonSNS =
    when (asyncClient) {
      false -> awsAmazonSNS(accessKeyId, secretAccessKey, region, useWebToken)
      true -> awsAmazonSNSAsync(accessKeyId, secretAccessKey, region, useWebToken)
    }.also { log.info("Created an AWS SNS client for topicId=$topicId") }

  fun localstackSnsClient(topicId: String, localstackUrl: String, region: String, asyncClient: Boolean): AmazonSNS =
    when (asyncClient) {
      false -> localstackAmazonSNS(localstackUrl, region)
      true -> localstackAmazonSNSAsync(localstackUrl, region)
    }.also { log.info("Created a LocalStack SNS client for topicId=$topicId") }

  private fun awsAmazonSNS(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean): AmazonSNS {
    val credentials = if (useWebToken) {
      DefaultAWSCredentialsProviderChain()
    } else {
      AWSStaticCredentialsProvider(BasicAWSCredentials(accessKeyId, secretAccessKey))
    }
    return AmazonSNSClientBuilder.standard()
      .withCredentials(credentials)
      .withRegion(region)
      .build()
  }

  private fun awsAmazonSNSAsync(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean): AmazonSNSAsync {
    val credentials = if (useWebToken) {
      DefaultAWSCredentialsProviderChain()
    } else {
      AWSStaticCredentialsProvider(BasicAWSCredentials(accessKeyId, secretAccessKey))
    }
    return AmazonSNSAsyncClientBuilder.standard()
      .withCredentials(credentials)
      .withRegion(region)
      .build()
  }

  private fun localstackAmazonSNS(localstackUrl: String, region: String) =
    AmazonSNSClientBuilder.standard()
      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(localstackUrl, region))
      .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials("any", "any"))) // LocalStack doesn't work with Anonymous credentials when dealing with topics but doesn't care what the credential values are.
      .build()

  private fun localstackAmazonSNSAsync(localstackUrl: String, region: String) =
    AmazonSNSAsyncClientBuilder.standard()
      .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(localstackUrl, region))
      .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials("any", "any"))) // LocalStack doesn't work with Anonymous credentials when dealing with topics but doesn't care what the credential values are.
      .build()
}
