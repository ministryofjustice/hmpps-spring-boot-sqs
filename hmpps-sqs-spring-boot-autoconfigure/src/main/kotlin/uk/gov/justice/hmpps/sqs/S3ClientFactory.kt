package uk.gov.justice.hmpps.sqs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import uk.gov.justice.hmpps.sqs.telemetry.TraceInjectingExecutionInterceptor
import java.net.URI

class S3ClientFactory {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
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