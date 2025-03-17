package uk.gov.justice.hmpps.sqs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.sns.AmazonSNSExtendedAsyncClient
import software.amazon.sns.SNSExtendedAsyncClientConfiguration
import uk.gov.justice.hmpps.sqs.telemetry.TraceInjectingExecutionInterceptor
import java.net.URI

class SnsClientFactory {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }
  fun awsSnsAsyncClient(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean, propagateTracing: Boolean): SnsAsyncClient {
    val credentialsProvider =
      if (useWebToken) {
        DefaultCredentialsProvider.builder().build()
      } else {
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey))
      }
    return SnsAsyncClient.builder()
      .credentialsProvider(credentialsProvider)
      .region(Region.of(region))
      .apply {
        if (propagateTracing) {
          overrideConfiguration { it.addExecutionInterceptor(TraceInjectingExecutionInterceptor()) }
        }
      }
      .build()
  }

  fun localstackSnsAsyncClient(
    localstackUrl: String,
    region: String,
    propagateTracing: Boolean,
    bucketName: String
  ): SnsAsyncClient {
    return when {
      bucketName.isBlank() -> snsAsyncClient(localstackUrl, region, propagateTracing)
      else -> awsSnsExtendedAsyncClient(localstackUrl, region, propagateTracing, bucketName)
    }
  }

  private fun snsAsyncClient(
    localstackUrl: String,
    region: String,
    propagateTracing: Boolean
  ): SnsAsyncClient = SnsAsyncClient.builder()
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any", "any")))
    .endpointOverride(URI.create(localstackUrl))
    .region(Region.of(region))
    .apply {
      if (propagateTracing) {
        overrideConfiguration { it.addExecutionInterceptor(TraceInjectingExecutionInterceptor()) }
      }
    }
    .build()

  fun awsSnsExtendedAsyncClient(localstackUrl: String, region: String, propagateTracing: Boolean, bucketName: String): SnsAsyncClient {
    val amazonS3AsyncClient = createS3AsyncClient(localstackUrl, region, propagateTracing)
    val snsExtendedAsyncClientConfiguration: SNSExtendedAsyncClientConfiguration = SNSExtendedAsyncClientConfiguration()
      .withAlwaysThroughS3(true)
      .withPayloadSupportEnabled(amazonS3AsyncClient, bucketName)
    val snsClient = snsAsyncClient(localstackUrl, region, propagateTracing)

    val sns = AmazonSNSExtendedAsyncClient(
      snsClient,
      snsExtendedAsyncClientConfiguration,
    )

    return sns
  }

  private fun createS3AsyncClient(localstackUrl: String, region: String, propagateTracing: Boolean): S3AsyncClient? {
    return S3AsyncClient.builder()
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any", "any")))
      .endpointOverride(URI.create(localstackUrl))
      .forcePathStyle(true)
      .region(Region.of(region))
      .apply {
        if (propagateTracing) {
          overrideConfiguration { it.addExecutionInterceptor(TraceInjectingExecutionInterceptor()) }
        }
      }
      .build()
  }

  private fun awsS3AsyncClient(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean, propagateTracing: Boolean): S3AsyncClient {
    val credentialsProvider =
      if (useWebToken) {
        DefaultCredentialsProvider.builder().build()
      } else {
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey))
      }
    return S3AsyncClient.builder()
      .credentialsProvider(credentialsProvider)
      .region(Region.of(region))
      .apply {
        if (propagateTracing) {
          overrideConfiguration { it.addExecutionInterceptor(TraceInjectingExecutionInterceptor()) }
        }
      }
      .build()
  }
}
