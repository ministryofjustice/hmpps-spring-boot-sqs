package uk.gov.justice.hmpps.sqs

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.ListBucketsRequest
import uk.gov.justice.hmpps.sqs.telemetry.TraceInjectingExecutionInterceptor
import java.net.URI

fun createLocalstackS3AsyncClient(
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
      if (it.listBuckets(ListBucketsRequest.builder().build()).await().buckets().none { it.name() == bucketName }) {
        it.createBucket(
          CreateBucketRequest.builder()
            .bucket(bucketName)
            .build(),
        ).await()
      }
    }
  }
  .also { SnsClientFactory.log.info("Created a LocalStack S3 Bucket named $bucketName") }