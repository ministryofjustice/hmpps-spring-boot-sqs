package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.health

import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension.Companion.oAuthApi

class QueueHealthCheckTest : IntegrationTestBase() {

  @Test
  fun `Main queue health ok`() {
    oAuthApi.stubHealthPing(200)

    webTestClient.get()
      .uri("/health")
      .exchange()
      .expectStatus()
      .isOk
      .expectBody()
      .jsonPath("status").isEqualTo("UP")
      .jsonPath("components.mainqueue-health.status").isEqualTo("UP")
      .jsonPath("components.mainqueue-health.details.queueName").isEqualTo(hmppsSqsPropertiesSpy.mainQueueConfig().queueName)
      .jsonPath("components.mainqueue-health.details.messagesOnQueue").isEqualTo(0)
      .jsonPath("components.mainqueue-health.details.messagesInFlight").isEqualTo(0)
      .jsonPath("components.mainqueue-health.details.dlqName").isEqualTo(hmppsSqsPropertiesSpy.mainQueueConfig().dlqName)
      .jsonPath("components.mainqueue-health.details.dlqStatus").isEqualTo("UP")
      .jsonPath("components.mainqueue-health.details.messagesOnDlq").isEqualTo(0)
  }

  @Test
  fun `Another queue health ok`() {
    oAuthApi.stubHealthPing(200)

    webTestClient.get()
      .uri("/health")
      .exchange()
      .expectStatus()
      .isOk
      .expectBody()
      .jsonPath("status").isEqualTo("UP")
      .jsonPath("components.anotherqueue-health.status").isEqualTo("UP")
      .jsonPath("components.anotherqueue-health.details.queueName").isEqualTo(hmppsSqsPropertiesSpy.anotherQueueConfig().queueName)
      .jsonPath("components.anotherqueue-health.details.messagesOnQueue").isEqualTo(0)
      .jsonPath("components.anotherqueue-health.details.messagesInFlight").isEqualTo(0)
      .jsonPath("components.anotherqueue-health.details.dlqName").isEqualTo(hmppsSqsPropertiesSpy.anotherQueueConfig().dlqName)
      .jsonPath("components.anotherqueue-health.details.dlqStatus").isEqualTo("UP")
      .jsonPath("components.anotherqueue-health.details.messagesOnDlq").isEqualTo(0)
  }
}
