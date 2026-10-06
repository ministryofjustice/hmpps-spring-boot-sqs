package uk.gov.justice.digital.hmpps.templatepackagename.integration.health

import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.templatepackagename.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.templatepackagename.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth

class TopicHealthCheckTest : IntegrationTestBase() {

  @Test
  fun `Inbound topic health ok`() {
    hmppsAuth.stubHealthPing(200)

    webTestClient.get()
      .uri("/health")
      .exchange()
      .expectStatus()
      .isOk
      .expectBody()
      .jsonPath("status").isEqualTo("UP")
      .jsonPath("components.inboundtopic-health.status").isEqualTo("UP")
      .jsonPath("components.inboundtopic-health.details.topicArn").isEqualTo(hmppsSqsPropertiesSpy.inboundTopicConfig().arn)
      .jsonPath("components.inboundtopic-health.details.subscriptionsConfirmed").isEqualTo(0)
      .jsonPath("components.inboundtopic-health.details.subscriptionsPending").isEqualTo(0)
  }

  @Test
  fun `Outbound queue health ok`() {
    hmppsAuth.stubHealthPing(200)

    webTestClient.get()
      .uri("/health")
      .exchange()
      .expectStatus()
      .isOk
      .expectBody()
      .jsonPath("status").isEqualTo("UP")
      .jsonPath("components.outboundtopic-health.status").isEqualTo("UP")
      .jsonPath("components.outboundtopic-health.details.topicArn").isEqualTo(hmppsSqsPropertiesSpy.outboundTopicConfig().arn)
      .jsonPath("components.outboundtopic-health.details.subscriptionsConfirmed").isEqualTo(0)
      .jsonPath("components.outboundtopic-health.details.subscriptionsPending").isEqualTo(0)
  }
}
