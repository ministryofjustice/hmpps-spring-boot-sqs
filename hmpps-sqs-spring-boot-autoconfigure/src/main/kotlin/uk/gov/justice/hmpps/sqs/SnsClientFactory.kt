package uk.gov.justice.hmpps.sqs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsClient
import java.net.URI

class SnsClientFactory {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }
  fun awsSnsClient(accessKeyId: String, secretAccessKey: String, region: String): SnsClient =
    SnsClient.builder()
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
      .region(Region.of(region))
      .build()

  fun localstackSnsClient(localstackUrl: String, region: String): SnsClient =
    SnsClient.builder()
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any", "any")))
      .endpointOverride(URI.create(localstackUrl))
      .region(Region.of(region))
      .build()
}
