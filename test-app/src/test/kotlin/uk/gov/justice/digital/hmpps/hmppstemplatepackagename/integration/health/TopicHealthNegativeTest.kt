package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.health

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sns.AmazonSNSClientBuilder
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension.Companion.oAuthApi
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties
import uk.gov.justice.hmpps.sqs.HmppsTopic
import uk.gov.justice.hmpps.sqs.HmppsTopicHealth

class TopicHealthNegativeTest : IntegrationTestBase() {

  @TestConfiguration
  class TestConfig {
    @Bean
    fun badTopicHealth(hmppsConfigProperties: HmppsSqsProperties): HmppsTopicHealth {
      val snsClient = AmazonSNSClientBuilder.standard()
        .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(hmppsConfigProperties.localstackUrl, hmppsConfigProperties.region))
        .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials("any", "any")))
        .build()
      return HmppsTopicHealth(HmppsTopic("missingTopicId", "missingTopicArn", snsClient))
    }
  }

  @Test
  fun `Topic health down`() {
    oAuthApi.stubHealthPing(200)

    webTestClient.get()
      .uri("/health")
      .exchange()
      .expectStatus()
      .is5xxServerError
      .expectBody()
      .jsonPath("status").isEqualTo("DOWN")
      .jsonPath("components.badTopicHealth.status").isEqualTo("DOWN")
      .jsonPath("components.badTopicHealth.details.topicArn").isEqualTo("missingTopicArn")
      .jsonPath("components.badTopicHealth.details.error").exists()
  }
}
