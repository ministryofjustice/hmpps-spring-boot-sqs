package uk.gov.justice.hmpps.sqs

import com.amazon.sqs.javamessaging.AmazonSQSExtendedAsyncClient
import com.amazon.sqs.javamessaging.ExtendedAsyncClientConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import uk.gov.justice.hmpps.sqs.telemetry.TraceInjectingExecutionInterceptor
import java.net.URI

class SqsClientFactory {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }
  fun awsSqsAsyncClient(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean, propagateTracing: Boolean): SqsAsyncClient {
    val credentialsProvider =
      if (useWebToken) {
        DefaultCredentialsProvider.builder().build()
      } else {
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey))
      }
    return SqsAsyncClient.builder()
      .credentialsProvider(credentialsProvider)
      .region(Region.of(region))
      .apply {
        if (propagateTracing) {
          overrideConfiguration { it.addExecutionInterceptor(TraceInjectingExecutionInterceptor()) }
        }
      }
      .build()
  }

  fun localstackSqsAsyncClient(localstackUrl: String, region: String, propagateTracing: Boolean, bucketName: String): SqsAsyncClient = when {
    bucketName.isBlank() -> localstackSqsAsyncClient(localstackUrl, region, propagateTracing)
    else -> localstackSqsAsyncExtendedClient(localstackUrl, region, propagateTracing, bucketName)
  }

  fun localstackSqsAsyncClient(localstackUrl: String, region: String, propagateTracing: Boolean): SqsAsyncClient = SqsAsyncClient.builder()
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any", "any")))
    .endpointOverride(URI.create(localstackUrl))
    .region(Region.of(region))
    .apply {
      if (propagateTracing) {
        overrideConfiguration { it.addExecutionInterceptor(TraceInjectingExecutionInterceptor()) }
      }
    }
    .build()

  fun localstackSqsAsyncExtendedClient(localstackUrl: String, region: String, propagateTracing: Boolean, bucketName: String): SqsAsyncClient {
    val sqsExtendedAsyncClientConfiguration: ExtendedAsyncClientConfiguration = ExtendedAsyncClientConfiguration()
      .withPayloadSupportEnabled(createLocalstackS3AsyncClient(localstackUrl, region, propagateTracing, bucketName), bucketName)
      .withAlwaysThroughS3(true)
    val sqsClient = localstackSqsAsyncClient(localstackUrl, region, propagateTracing)
    return AmazonSQSExtendedAsyncClient(sqsClient, sqsExtendedAsyncClientConfiguration)
  }
}
