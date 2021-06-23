package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.health

import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension.Companion.oAuthApi

class QueueHealthCheckTest : IntegrationTestBase() {

  private val healthBeanName by lazy { "${sqsConfigProperties.id}-health" }

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
      .jsonPath("components.$healthBeanName.status").isEqualTo("UP")
      .jsonPath("components.$healthBeanName.details.queueName").isEqualTo(sqsConfigProperties.queueName)
      .jsonPath("components.$healthBeanName.details.messagesOnQueue").isEqualTo(0)
      .jsonPath("components.$healthBeanName.details.messagesInFlight").isEqualTo(0)
      .jsonPath("components.$healthBeanName.details.dlqStatus").isEqualTo("UP")
      .jsonPath("components.$healthBeanName.details.messagesOnDlq").isEqualTo(0)
  }
}
