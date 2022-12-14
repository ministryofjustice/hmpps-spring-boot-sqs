package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration.health

import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration.mocks.OAuthExtension.Companion.oAuthApi

class QueueHealthCheckTest : IntegrationTestBase() {

  @Test
  fun `Inbound queue health ok`() {
    oAuthApi.stubHealthPing(200)

    webTestClient.get()
      .uri("/health")
      .exchange()
      .expectStatus()
      .isOk
      .expectBody()
      .jsonPath("status").isEqualTo("UP")
      .jsonPath("components.inboundqueue-health.status").isEqualTo("UP")
      .jsonPath("components.inboundqueue-health.details.queueName").isEqualTo(hmppsSqsPropertiesSpy.inboundQueueConfig().queueName)
      .jsonPath("components.inboundqueue-health.details.messagesOnQueue").isEqualTo(0)
      .jsonPath("components.inboundqueue-health.details.messagesInFlight").isEqualTo(0)
      .jsonPath("components.inboundqueue-health.details.dlqName").isEqualTo(hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName)
      .jsonPath("components.inboundqueue-health.details.dlqStatus").isEqualTo("UP")
      .jsonPath("components.inboundqueue-health.details.messagesOnDlq").isEqualTo(0)
  }

  @Test
  fun `Outbound queue health ok`() {
    oAuthApi.stubHealthPing(200)

    webTestClient.get()
      .uri("/health")
      .exchange()
      .expectStatus()
      .isOk
      .expectBody()
      .jsonPath("status").isEqualTo("UP")
      .jsonPath("components.outboundqueue-health.status").isEqualTo("UP")
      .jsonPath("components.outboundqueue-health.details.queueName").isEqualTo(hmppsSqsPropertiesSpy.outboundQueueConfig().queueName)
      .jsonPath("components.outboundqueue-health.details.messagesOnQueue").isEqualTo(0)
      .jsonPath("components.outboundqueue-health.details.messagesInFlight").isEqualTo(0)
      .jsonPath("components.outboundqueue-health.details.dlqName").isEqualTo(hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName)
      .jsonPath("components.outboundqueue-health.details.dlqStatus").isEqualTo("UP")
      .jsonPath("components.outboundqueue-health.details.messagesOnDlq").isEqualTo(0)
  }

  @Test
  fun `Async queue health ok`() {
    oAuthApi.stubHealthPing(200)

    webTestClient.get()
      .uri("/health")
      .exchange()
      .expectStatus()
      .isOk
      .expectBody()
      .jsonPath("status").isEqualTo("UP")
      .jsonPath("components.asyncqueue-health.status").isEqualTo("UP")
      .jsonPath("components.asyncqueue-health.details.queueName").isEqualTo(hmppsSqsPropertiesSpy.asyncQueueConfig().queueName)
      .jsonPath("components.asyncqueue-health.details.messagesOnQueue").isEqualTo(0)
      .jsonPath("components.asyncqueue-health.details.messagesInFlight").isEqualTo(0)
      .jsonPath("components.asyncqueue-health.details.dlqName").isEqualTo(hmppsSqsPropertiesSpy.asyncQueueConfig().dlqName)
      .jsonPath("components.asyncqueue-health.details.dlqStatus").isEqualTo("UP")
      .jsonPath("components.asyncqueue-health.details.messagesOnDlq").isEqualTo(0)
  }
}
