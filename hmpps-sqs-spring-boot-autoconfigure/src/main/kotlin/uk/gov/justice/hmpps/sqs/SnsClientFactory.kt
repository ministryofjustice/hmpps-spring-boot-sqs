package uk.gov.justice.hmpps.sqs

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.ListBucketsRequest
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.sns.AmazonSNSExtendedAsyncClient
import software.amazon.sns.SNSExtendedAsyncClientConfiguration
import uk.gov.justice.hmpps.sqs.telemetry.TraceInjectingExecutionInterceptor
import java.net.URI

class SnsClientFactory(val context: ConfigurableApplicationContext) {
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
    bucketName: String,
  ): SnsAsyncClient = when {
    bucketName.isBlank() -> snsAsyncClient(localstackUrl, region, propagateTracing)
    else -> localstackSnsExtendedAsyncClient(localstackUrl, region, propagateTracing, bucketName)
  }

  private fun snsAsyncClient(
    localstackUrl: String,
    region: String,
    propagateTracing: Boolean,
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

  private fun localstackSnsExtendedAsyncClient(localstackUrl: String, region: String, propagateTracing: Boolean, bucketName: String): SnsAsyncClient {
    val amazonS3AsyncClient = getOrDefaultS3AsyncClient(localstackUrl, region, propagateTracing, "localstack", bucketName)
    val snsExtendedAsyncClientConfiguration: SNSExtendedAsyncClientConfiguration = SNSExtendedAsyncClientConfiguration()
      .withPayloadSupportEnabled(amazonS3AsyncClient, bucketName)
    val snsClient = snsAsyncClient(localstackUrl, region, propagateTracing)

    val sns = AmazonSNSExtendedAsyncClient(
      snsClient,
      snsExtendedAsyncClientConfiguration,
    )
    return sns
  }

  private fun createLocalstackS3AsyncClient(localstackUrl: String, region: String, propagateTracing: Boolean): S3AsyncClient? = S3AsyncClient.builder()
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

  private fun getOrDefaultS3AsyncClient(localstackUrl: String, region: String, propagateTracing: Boolean, provider: String, bucketName: String): S3AsyncClient = "$bucketName-s3-client".let { beanName ->
    runCatching { context.beanFactory.getBean(beanName) as S3AsyncClient }
      .getOrElse {
        createS3AsyncClient(localstackUrl, region, propagateTracing, provider, bucketName)
          .also { context.beanFactory.registerSingleton(beanName, it) }
      }
  }

  private fun createS3AsyncClient(localstackUrl: String, region: String, propagateTracing: Boolean, provider: String, bucketName: String): S3AsyncClient = when (provider) {
    // "aws" -> awsS3AsyncClient(s3Config.accessKeyId, s3Config.secretAccessKey, region, hmppsSqsProperties.useWebToken, s3Config.propagateTracing)
    "localstack" -> localstackS3AsyncClient(localstackUrl, region, false) // todo
      .also {
        runBlocking {
          if (it.listBuckets(ListBucketsRequest.builder().build()).await().buckets().none { it.name() == bucketName }) {
            it.createBucket(
              CreateBucketRequest.builder()
                .bucket(bucketName)
                .build(),
            ).await()
          }
        }
      }
      .also { HmppsTopicFactory.log.info("Created a LocalStack S3 Bucket named $bucketName") }
    else -> throw IllegalStateException("Unrecognised HMPPS S3 provider $provider")
  }

  fun awsS3AsyncClient(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean, propagateTracing: Boolean): S3AsyncClient {
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

  fun localstackS3AsyncClient(localstackUrl: String, region: String, propagateTracing: Boolean): S3AsyncClient = S3AsyncClient.builder()
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
