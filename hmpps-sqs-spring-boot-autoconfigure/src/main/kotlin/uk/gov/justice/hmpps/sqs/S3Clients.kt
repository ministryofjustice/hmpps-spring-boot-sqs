package uk.gov.justice.hmpps.sqs

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.ListBucketsRequest
import uk.gov.justice.hmpps.sqs.telemetry.TraceInjectingExecutionInterceptor
import java.lang.invoke.MethodHandles
import java.net.URI

private val log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

internal fun awsS3AsyncClient(region: String, propagateTracing: Boolean): S3AsyncClient = S3AsyncClient.builder()
  .credentialsProvider(DefaultCredentialsProvider.builder().build())
  .region(Region.of(region))
  .apply {
    if (propagateTracing) {
      overrideConfiguration { it.addExecutionInterceptor(TraceInjectingExecutionInterceptor()) }
    }
  }
  .build()

internal fun localstackS3AsyncClient(
  localstackUrl: String,
  region: String,
  propagateTracing: Boolean,
  bucketName: String,
): S3AsyncClient = S3AsyncClient.builder()
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
  .also {
    runBlocking {
      if (it.listBuckets(ListBucketsRequest.builder().build()).await().buckets().none { it.name() == bucketName }) { // This is in case the same bucket name is used to back multiple queues/topics in test setup
        it.createBucket(
          CreateBucketRequest.builder()
            .bucket(bucketName)
            .build(),
        ).await()
      }
    }
  }
  .also { log.info("Created a LocalStack S3 Bucket named $bucketName") }
