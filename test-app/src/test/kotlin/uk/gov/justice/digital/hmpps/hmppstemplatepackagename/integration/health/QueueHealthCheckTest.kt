package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.health

import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config.mainQueue
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension.Companion.oAuthApi

class QueueHealthCheckTest : IntegrationTestBase() {

  @Test
  fun `Queue health ok`() {
    oAuthApi.stubHealthPing(200)

    webTestClient.get()
      .uri("/health")
      .exchange()
      .expectStatus()
      .isOk
      .expectBody()
      .jsonPath("status").isEqualTo("UP")
      .jsonPath("components.mainQueue-health.status").isEqualTo("UP")
      .jsonPath("components.mainQueue-health.details.queueName").isEqualTo(hmppsQueueProperties.mainQueue().queueName)
      .jsonPath("components.mainQueue-health.details.messagesOnQueue").isEqualTo(0)
      .jsonPath("components.mainQueue-health.details.messagesInFlight").isEqualTo(0)
      .jsonPath("components.mainQueue-health.details.dlqStatus").isEqualTo("UP")
      .jsonPath("components.mainQueue-health.details.messagesOnDlq").isEqualTo(0)
  }
}
