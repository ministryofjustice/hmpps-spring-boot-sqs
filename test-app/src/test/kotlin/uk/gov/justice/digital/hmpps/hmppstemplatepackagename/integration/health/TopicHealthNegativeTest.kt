package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.health

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension.Companion.oAuthApi
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties
import uk.gov.justice.hmpps.sqs.HmppsTopic
import uk.gov.justice.hmpps.sqs.HmppsTopicHealth
import java.net.URI

class TopicHealthNegativeTest : IntegrationTestBase() {

  @TestConfiguration
  class TestConfig {
    @Bean
    fun badTopicHealth(hmppsConfigProperties: HmppsSqsProperties): HmppsTopicHealth {
      val snsClient = SnsAsyncClient.builder()
        .endpointOverride(URI.create(hmppsConfigProperties.localstackUrl))
        .region(Region.of(hmppsConfigProperties.region))
        .credentialsProvider(AnonymousCredentialsProvider.create())
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
