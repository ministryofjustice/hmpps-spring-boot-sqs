package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["hmpps.sqs.queueAdminRole=ROLE_TEST_APP_QUEUE_ADMIN", "hmpps.sqs.protectRetryAll=true"])
class HmppsQueueAdminTest : IntegrationTestBase() {

  @Test
  fun `should not allow purge with the default role`() {
    webTestClient.put()
      .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `should allow purge with custom queue admin role`() {
    webTestClient.put()
      .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
      .headers { it.authToken(roles = listOf("ROLE_TEST_APP_QUEUE_ADMIN")) }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk
  }

  @Test
  fun `should allow retry all with retry queue admin role`() {
    webTestClient.put()
      .uri("/queue-admin/retry-all-dlqs")
      .headers { it.authToken(roles = listOf("ROLE_TEST_APP_QUEUE_ADMIN")) }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk
  }

  @Test
  fun `should not allow retry all with the default role`() {
    webTestClient.put()
      .uri("/queue-admin/retry-all-dlqs")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `should not allow retry all with no authorisation`() {
    webTestClient.put()
      .uri("/queue-admin/retry-all-dlqs")
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `should not allow retry dlq with the default role`() {
    webTestClient.put()
      .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `should allow retry dlq with custom queue admin role`() {
    webTestClient.put()
      .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
      .headers { it.authToken(roles = listOf("ROLE_TEST_APP_QUEUE_ADMIN")) }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk
  }
}
