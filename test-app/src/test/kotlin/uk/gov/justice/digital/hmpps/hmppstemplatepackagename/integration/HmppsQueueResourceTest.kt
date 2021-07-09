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
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.EventType
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.Message
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.MessageAttributes

class HmppsQueueResourceTest : IntegrationTestBase() {

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  inner class SecureEndpoints {
    private fun secureEndpoints() =
      listOf(
        "/queue-admin/retry-dlq/any-queue",
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
    fun `should transfer messages from inbound DLQ to inbound queue and process them`() {
      val event1 = HmppsEvent("id1", "test.type", "message3")
      val event2 = HmppsEvent("id2", "test.type", "message4")
      val message1 = Message(gsonString(event1), "message-id1", MessageAttributes(EventType("test.type", "String")))
      val message2 = Message(gsonString(event2), "message-id2", MessageAttributes(EventType("test.type", "String")))
      inboundSqsDlqClient.sendMessage(inboundDlqUrl, gsonString(message1))
      inboundSqsDlqClient.sendMessage(inboundDlqUrl, gsonString(message2))
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl) } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl) } matches { it == 0 }
      await untilCallTo { inboundSqsClient.countMessagesOnQueue(inboundQueueUrl) } matches { it == 0 }

      verify(inboundMessageServiceSpy).handleMessage(event1)
      verify(inboundMessageServiceSpy).handleMessage(event2)
    }

    @Test
    fun `should transfer messages from outbound DLQ to outbound queue and process them`() {
      val event3 = HmppsEvent("id3", "test.type", "message3")
      val event4 = HmppsEvent("id4", "test.type", "message4")
      val message3 = Message(gsonString(event3), "message-id3", MessageAttributes(EventType("test.type", "String")))
      val message4 = Message(gsonString(event4), "message-id4", MessageAttributes(EventType("test.type", "String")))
      outboundSqsDlqClientSpy.sendMessage(outboundDlqUrl, gsonString(message3))
      outboundSqsDlqClientSpy.sendMessage(outboundDlqUrl, gsonString(message4))
      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 0 }
      await untilCallTo { outboundSqsClientSpy.countMessagesOnQueue(outboundQueueUrl) } matches { it == 0 }

      verify(outboundMessageServiceSpy).handleMessage(event3)
      verify(outboundMessageServiceSpy).handleMessage(event4)
    }
  }

  @Nested
  inner class RetryAllDlqs {
    @Test
    fun `should transfer messages from DLQ to inbound queue and process them`() {
      val event5 = HmppsEvent("id5", "test.type", "message5")
      val event6 = HmppsEvent("id6", "test.type", "message6")
      val message5 = Message(gsonString(event5), "message-id5", MessageAttributes(EventType("test.type", "String")))
      val message6 = Message(gsonString(event6), "message-id6", MessageAttributes(EventType("test.type", "String")))
      inboundSqsDlqClient.sendMessage(inboundDlqUrl, gsonString(message5))
      outboundSqsDlqClientSpy.sendMessage(outboundDlqUrl, gsonString(message6))
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl) } matches { it == 1 }
      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 1 }

      webTestClient.put()
        .uri("/queue-admin/retry-all-dlqs")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl) } matches { it == 0 }
      await untilCallTo { inboundSqsClient.countMessagesOnQueue(inboundQueueUrl) } matches { it == 0 }
      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 0 }
      await untilCallTo { outboundSqsClientSpy.countMessagesOnQueue(outboundQueueUrl) } matches { it == 0 }

      verify(inboundMessageServiceSpy).handleMessage(event5)
      verify(outboundMessageServiceSpy).handleMessage(event6)
    }
  }

  @Nested
  inner class PurgeQueue {
    @Test
    fun `should purge the inbound dlq`() {
      inboundSqsDlqClient.sendMessage(inboundDlqUrl, gsonString(HmppsEvent("id1", "test.type", "message1")))
      inboundSqsDlqClient.sendMessage(inboundDlqUrl, gsonString(HmppsEvent("id2", "test.type", "message2")))
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl) } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl) } matches { it == 0 }
    }

    @Test
    fun `should purge the outbound dlq`() {
      outboundSqsDlqClientSpy.sendMessage(outboundDlqUrl, gsonString(HmppsEvent("id3", "test.type", "message3")))
      outboundSqsDlqClientSpy.sendMessage(outboundDlqUrl, gsonString(HmppsEvent("id4", "test.type", "message4")))
      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 0 }
    }
  }
}
