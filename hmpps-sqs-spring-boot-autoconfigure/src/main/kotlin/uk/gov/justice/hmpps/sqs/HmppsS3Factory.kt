package uk.gov.justice.hmpps.sqs

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.ListBucketsRequest

class HmppsS3Factory(
  private val context: ConfigurableApplicationContext,
  private val s3ClientFactory: S3ClientFactory,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createS3Bucket(hmppsS3Properties: HmppsSqsProperties): List<HmppsS3Bucket> =
    hmppsS3Properties.buckets
      .map { (bucketName, s3Config) ->
        val s3Client = getOrDefaultS3AsyncClient(bucketName, s3Config, hmppsS3Properties)
        HmppsS3Bucket(bucketName, s3Client)
      }.toList()

  private fun getOrDefaultS3AsyncClient(bucketName: String, s3Config: HmppsSqsProperties.BucketConfig, hmppsSqsProperties: HmppsSqsProperties): S3AsyncClient =
    "$bucketName-s3-client".let { beanName ->
      runCatching { context.beanFactory.getBean(beanName) as S3AsyncClient }
        .getOrElse {
          createS3AsyncClient(bucketName, s3Config, hmppsSqsProperties)
            .also { context.beanFactory.registerSingleton(beanName, it) }
        }
    }

  private fun createS3AsyncClient(bucketName: String, s3Config: HmppsSqsProperties.BucketConfig, hmppsSqsProperties: HmppsSqsProperties): S3AsyncClient =
    with(hmppsSqsProperties) {
      when (provider) {
        "aws" -> s3ClientFactory.awsS3AsyncClient(s3Config.accessKeyId, s3Config.secretAccessKey, region, hmppsSqsProperties.useWebToken, s3Config.propagateTracing)
        "localstack" -> s3ClientFactory.localstackS3AsyncClient(localstackUrl, region, s3Config.propagateTracing)
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
          .also { log.info("Created a LocalStack S3 Bucket named $bucketName") }
        else -> throw IllegalStateException("Unrecognised HMPPS S3 provider $provider")
      }
    }
}
