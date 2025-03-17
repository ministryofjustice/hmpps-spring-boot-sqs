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
import software.amazon.awssdk.services.sns.model.CreateTopicRequest
import uk.gov.justice.hmpps.sqs.telemetry.TraceInjectingExecutionInterceptor
import java.net.URI

class HmppsTopicFactory(
  private val context: ConfigurableApplicationContext,
  private val healthContributorRegistry: HmppsHealthContributorRegistry,
  private val snsClientFactory: SnsClientFactory,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createHmppsTopics(hmppsSqsProperties: HmppsSqsProperties) = hmppsSqsProperties.topics
    .map { (topicId, topicConfig) ->
      val snsClient = getOrDefaultSnsAsyncClient(topicId, topicConfig, hmppsSqsProperties)
      HmppsTopic(topicId, topicConfig.arn, snsClient)
        .also { registerHealthIndicator(it) }
    }.toList()

  private fun registerHealthIndicator(topic: HmppsTopic) {
    healthContributorRegistry.registerContributor("${topic.id}-health") {
      HmppsTopicHealth(topic)
    }
  }

  private fun getOrDefaultSnsAsyncClient(topicId: String, topicConfig: HmppsSqsProperties.TopicConfig, hmppsSqsProperties: HmppsSqsProperties): SnsAsyncClient = "$topicId-sns-client".let { beanName ->
    runCatching { context.beanFactory.getBean(beanName) as SnsAsyncClient }
      .getOrElse {
        createSnsAsyncClient(topicId, topicConfig, hmppsSqsProperties)
          .also { context.beanFactory.registerSingleton(beanName, it) }
      }
  }

  fun createSnsAsyncClient(topicId: String, topicConfig: HmppsSqsProperties.TopicConfig, hmppsSqsProperties: HmppsSqsProperties): SnsAsyncClient = with(hmppsSqsProperties) {
    when (provider) {
      "aws" -> snsClientFactory.awsSnsAsyncClient(topicConfig.accessKeyId, topicConfig.secretAccessKey, region, hmppsSqsProperties.useWebToken, topicConfig.propagateTracing)
      "localstack" -> snsClientFactory.localstackSnsAsyncClient(localstackUrl, region, topicConfig.propagateTracing, topicConfig.bucketName)
        .also {
          runBlocking {
            val attributes = when {
              topicConfig.isFifo() -> mapOf(
                "FifoTopic" to "true",
                "ContentBasedDeduplication" to "true",
              ) else -> mapOf()
            }
            it.createTopic(
              CreateTopicRequest.builder()
                .name(topicConfig.name)
                .attributes(attributes)
                .build(),
            ).await()
            if(topicConfig.bucketName.isNotEmpty()) {  createS3Bucket(hmppsSqsProperties,topicConfig)}
          }
        }
        .also { log.info("Created a LocalStack SNS topic for topicId $topicId with ARN ${topicConfig.arn}") }
      else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
    }
  }


  private fun createS3Bucket(hmppsSqsProperties: HmppsSqsProperties, topicConfig: HmppsSqsProperties.TopicConfig){

        getOrDefaultS3AsyncClient(hmppsSqsProperties, topicConfig)

      }

  private fun getOrDefaultS3AsyncClient(
    hmppsSqsProperties: HmppsSqsProperties,
    topicConfig: HmppsSqsProperties.TopicConfig
  ): S3AsyncClient =
    "${topicConfig.bucketName}-s3-client".let { beanName ->
      runCatching { context.beanFactory.getBean(beanName) as S3AsyncClient }
        .getOrElse {
          createS3AsyncClient(hmppsSqsProperties, topicConfig)
            .also { context.beanFactory.registerSingleton(beanName, it) }
        }
    }

  private fun createS3AsyncClient(hmppsSqsProperties: HmppsSqsProperties, topicConfig: HmppsSqsProperties.TopicConfig): S3AsyncClient =
    with(hmppsSqsProperties) {
      when (provider) {
        // "aws" -> awsS3AsyncClient(s3Config.accessKeyId, s3Config.secretAccessKey, region, hmppsSqsProperties.useWebToken, s3Config.propagateTracing)
        "localstack" -> localstackS3AsyncClient(localstackUrl, region, false) //todo
          .also {
            runBlocking {
              if (it.listBuckets(ListBucketsRequest.builder().build()).await().buckets().none { it.name() == topicConfig.bucketName }) {
                it.createBucket(
                  CreateBucketRequest.builder()
                    .bucket(topicConfig.bucketName)
                    .build(),
                ).await()
              }
            }
          }
          .also { log.info("Created a LocalStack S3 Bucket named $topicConfig.bucketName") }
        else -> throw IllegalStateException("Unrecognised HMPPS S3 provider $provider")
      }
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

  fun localstackS3AsyncClient(localstackUrl: String, region: String, propagateTracing: Boolean): S3AsyncClient =
    S3AsyncClient.builder()
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
