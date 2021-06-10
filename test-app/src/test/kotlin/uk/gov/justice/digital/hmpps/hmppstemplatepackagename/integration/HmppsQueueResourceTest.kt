package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.nhaarman.mockitokotlin2.verify
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class HmppsQueueResourceTest : IntegrationTestBase() {

  @Test
  fun `should fail if no auth token`() {
    webTestClient.put()
      .uri("/queue-admin/retry-dlq/${sqsConfigProperties.dlqName}")
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `should fail if required role not present`() {
    webTestClient.put()
      .uri("/queue-admin/retry-dlq/${sqsConfigProperties.dlqName}")
      .headers { it.authToken(roles = listOf("WRONG_ROLE")) }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `should fail if dlq not found`() {
    webTestClient.put()
      .uri("/queue-admin/retry-dlq/UNKNOWN_DLQ")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isNotFound
  }

  @Test
  fun `should transfer messages from DLQ to main queue and process them`() {
    sqsDlqClient.sendMessage(dlqUrl, "message1")
    sqsDlqClient.sendMessage(dlqUrl, "message2")
    await untilCallTo { sqsDlqClient.countMessagesOnQueue(dlqUrl) } matches { it == 2 }

    webTestClient.put()
      .uri("/queue-admin/retry-dlq/${sqsConfigProperties.dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    await untilCallTo { sqsDlqClient.countMessagesOnQueue(dlqUrl) } matches { it == 0 }
    await untilCallTo { sqsClient.countMessagesOnQueue(queueUrl) } matches { it == 0 }

    verify(messageServiceSpy).handleMessage("message1")
    verify(messageServiceSpy).handleMessage("message2")
  }
}
