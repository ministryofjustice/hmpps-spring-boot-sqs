package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.verify
import org.junit.jupiter.api.Test

class HmppsQueueSpyBeanTest : IntegrationTestBase() {

  @Test
  fun `Can verify usage of spy bean for Health page`() {
    webTestClient.get()
      .uri("/health")
      .exchange()
      .expectStatus()
      .isOk
      .expectBody()
      .jsonPath("status").isEqualTo("UP")

    verify(outboundSqsClientSpy).getQueueAttributes(any())
  }
}
