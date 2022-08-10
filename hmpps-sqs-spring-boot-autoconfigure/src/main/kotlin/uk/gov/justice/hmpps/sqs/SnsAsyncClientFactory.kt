package uk.gov.justice.hmpps.sqs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import java.net.URI

class SnsAsyncClientFactory {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }
  fun awsSnsAsyncClient(accessKeyId: String, secretAccessKey: String, region: String): SnsAsyncClient =
    SnsAsyncClient.builder()
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
      .region(Region.of(region))
      .build()

  fun localstackSnsAsyncClient(localstackUrl: String, region: String): SnsAsyncClient =
    SnsAsyncClient.builder()
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any", "any")))
      .endpointOverride(URI.create(localstackUrl))
      .region(Region.of(region))
      .build()
}
