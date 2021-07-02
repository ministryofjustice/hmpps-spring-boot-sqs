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
      .jsonPath("components.mainQueue-health.status").isEqualTo("UP")
      .jsonPath("components.mainQueue-health.details.queueName").isEqualTo(hmppsQueueProperties.mainQueueConfig().queueName)
      .jsonPath("components.mainQueue-health.details.messagesOnQueue").isEqualTo(0)
      .jsonPath("components.mainQueue-health.details.messagesInFlight").isEqualTo(0)
      .jsonPath("components.mainQueue-health.details.dlqName").isEqualTo(hmppsQueueProperties.mainQueueConfig().dlqName)
      .jsonPath("components.mainQueue-health.details.dlqStatus").isEqualTo("UP")
      .jsonPath("components.mainQueue-health.details.messagesOnDlq").isEqualTo(0)
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
      .jsonPath("components.anotherQueue-health.status").isEqualTo("UP")
      .jsonPath("components.anotherQueue-health.details.queueName").isEqualTo(hmppsQueueProperties.anotherQueueConfig().queueName)
      .jsonPath("components.anotherQueue-health.details.messagesOnQueue").isEqualTo(0)
      .jsonPath("components.anotherQueue-health.details.messagesInFlight").isEqualTo(0)
      .jsonPath("components.anotherQueue-health.details.dlqName").isEqualTo(hmppsQueueProperties.anotherQueueConfig().dlqName)
      .jsonPath("components.anotherQueue-health.details.dlqStatus").isEqualTo("UP")
      .jsonPath("components.anotherQueue-health.details.messagesOnDlq").isEqualTo(0)
  }
}
