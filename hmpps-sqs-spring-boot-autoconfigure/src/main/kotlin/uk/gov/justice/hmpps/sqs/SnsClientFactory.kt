package uk.gov.justice.hmpps.sqs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
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

class SnsClientFactory(val context: ConfigurableApplicationContext) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun awsSnsAsyncClient(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean, propagateTracing: Boolean, bucketName: String): SnsAsyncClient = when {
    bucketName.isBlank() -> awsSnsAsyncClient(accessKeyId, secretAccessKey, region, useWebToken, propagateTracing)
    else -> awsSnsExtendedAsyncClient(accessKeyId, secretAccessKey, region, useWebToken, propagateTracing, bucketName)
  }

  private fun awsSnsExtendedAsyncClient(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean, propagateTracing: Boolean, bucketName: String): SnsAsyncClient {
    val amazonS3AsyncClient = awsS3AsyncClient(region, propagateTracing)
    val snsExtendedAsyncClientConfiguration: SNSExtendedAsyncClientConfiguration = SNSExtendedAsyncClientConfiguration()
      .withPayloadSupportEnabled(amazonS3AsyncClient, bucketName)
    val snsClient = awsSnsAsyncClient(accessKeyId, secretAccessKey, region, useWebToken, propagateTracing)

    val sns = AmazonSNSExtendedAsyncClient(
      snsClient,
      snsExtendedAsyncClientConfiguration,
    )
    return sns
  }

  private fun awsSnsAsyncClient(accessKeyId: String, secretAccessKey: String, region: String, useWebToken: Boolean, propagateTracing: Boolean): SnsAsyncClient {
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
    topicConfig: HmppsSqsProperties.TopicConfig,
  ): SnsAsyncClient = when {
    topicConfig.bucketName.isBlank() -> localstackSnsAsyncClient(localstackUrl, region, topicConfig.propagateTracing)
    else -> localstackSnsExtendedAsyncClient(localstackUrl, region, topicConfig.propagateTracing, topicConfig.bucketName)
  }

  private fun localstackSnsAsyncClient(
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
    val amazonS3AsyncClient = getOrDefaultLocalstackS3AsyncClient(localstackUrl, region, propagateTracing, bucketName)
    val snsExtendedAsyncClientConfiguration: SNSExtendedAsyncClientConfiguration = SNSExtendedAsyncClientConfiguration()
      .withPayloadSupportEnabled(amazonS3AsyncClient, bucketName)
    val snsClient = localstackSnsAsyncClient(localstackUrl, region, propagateTracing)

    val sns = AmazonSNSExtendedAsyncClient(
      snsClient,
      snsExtendedAsyncClientConfiguration,
    )
    return sns
  }

  private fun getOrDefaultLocalstackS3AsyncClient(
    localstackUrl: String,
    region: String,
    propagateTracing: Boolean,
    bucketName: String,
  ): S3AsyncClient = "$bucketName-s3-client".let { beanName ->
    runCatching { context.beanFactory.getBean(beanName) as S3AsyncClient }
      .getOrElse {
        localstackS3AsyncClient(localstackUrl, region, propagateTracing, bucketName)
          .also { context.beanFactory.registerSingleton(beanName, it) }
      }
  }
}
