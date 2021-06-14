package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.nhaarman.mockitokotlin2.verify
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.MediaType

class HmppsQueueResourceTest : IntegrationTestBase() {

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  inner class SecureEndpoints {
    private fun secureEndpoints() =
      listOf(
        "/queue-admin/retry-dlq/any-queue",
        "/queue-admin/retry-all-dlqs",
        "/queue-admin/purge-queue/any-queue",
      )

    @ParameterizedTest
    @MethodSource("secureEndpoints")
    internal fun `requires a valid authentication token`(uri: String) {
      webTestClient.put()
        .uri(uri)
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isUnauthorized
    }

    @ParameterizedTest
    @MethodSource("secureEndpoints")
    internal fun `requires the correct role`(uri: String) {
      webTestClient.put()
        .uri(uri)
        .headers { it.authToken(roles = listOf("WRONG_ROLE")) }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isForbidden
    }
  }

  @Nested
  inner class RetryDlq {
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

  @Nested
  inner class RetryAllDlqs {
    // TODO when we start handling multiple queues add another queue/dlq combination to this test
    @Test
    fun `should transfer messages from DLQ to main queue and process them`() {
      sqsDlqClient.sendMessage(dlqUrl, "message1")
      sqsDlqClient.sendMessage(dlqUrl, "message2")
      await untilCallTo { sqsDlqClient.countMessagesOnQueue(dlqUrl) } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/retry-all-dlqs")
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

  @Nested
  inner class PurgeQueue {
    @Test
    fun `should purge the dlq`() {
      sqsDlqClient.sendMessage(dlqUrl, "message1")
      sqsDlqClient.sendMessage(dlqUrl, "message2")
      await untilCallTo { sqsDlqClient.countMessagesOnQueue(dlqUrl) } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/purge-queue/${sqsConfigProperties.dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { sqsDlqClient.countMessagesOnQueue(dlqUrl) } matches { it == 0 }
    }
  }
}
