package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import kotlinx.coroutines.test.runTest
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.verify
import org.springframework.http.MediaType
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.EventType
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.Message
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.MessageAttributes
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue

class HmppsQueueResourceTest : IntegrationTestBase() {

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  inner class SecureEndpoints {
    private fun secureEndpoints() = listOf(
      "/queue-admin/retry-dlq/any-queue",
      "/queue-admin/purge-queue/any-queue",
    )

    @ParameterizedTest
    @MethodSource("secureEndpoints")
    fun `requires a valid authentication token`(uri: String) {
      webTestClient.put()
        .uri(uri)
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isUnauthorized
    }

    @ParameterizedTest
    @MethodSource("secureEndpoints")
    fun `requires the correct role`(uri: String) {
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
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(gsonString(message1)).build())
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(gsonString(message2)).build())
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 0 }
      await untilCallTo { inboundSqsClient.countMessagesOnQueue(inboundQueueUrl).get() } matches { it == 0 }

      runTest {
        verify(inboundMessageServiceSpy).handleMessage(event1)
        verify(inboundMessageServiceSpy).handleMessage(event2)
      }
    }

    @Test
    fun `should transfer messages from outbound DLQ to outbound queue and process them`() {
      val event3 = HmppsEvent("id3", "test.type", "message3")
      val event4 = HmppsEvent("id4", "test.type", "message4")
      val message3 = Message(gsonString(event3), "message-id3", MessageAttributes(EventType("test.type", "String")))
      val message4 = Message(gsonString(event4), "message-id4", MessageAttributes(EventType("test.type", "String")))
      outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(gsonString(message3)).build())
      outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(gsonString(message4)).build())
      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 0 }
      await untilCallTo { outboundSqsClientSpy.countMessagesOnQueue(outboundQueueUrl).get() } matches { it == 0 }

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
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(gsonString(message5)).build())
      outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(gsonString(message6)).build())
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }
      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 1 }

      webTestClient.put()
        .uri("/queue-admin/retry-all-dlqs")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 0 }
      await untilCallTo { inboundSqsClient.countMessagesOnQueue(inboundQueueUrl).get() } matches { it == 0 }
      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 0 }
      await untilCallTo { outboundSqsClientSpy.countMessagesOnQueue(outboundQueueUrl).get() } matches { it == 0 }

      runTest {
        verify(inboundMessageServiceSpy).handleMessage(event5)
      }
      verify(outboundMessageServiceSpy).handleMessage(event6)
    }
  }

  @Nested
  inner class PurgeQueue {
    @Test
    fun `should purge the inbound dlq`() {
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(gsonString(HmppsEvent("id1", "test.type", "message1"))).build())
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(gsonString(HmppsEvent("id2", "test.type", "message2"))).build())
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 0 }
    }

    @Test
    fun `should purge the outbound dlq`() {
      outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(gsonString(HmppsEvent("id3", "test.type", "message3"))).build())
      outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(gsonString(HmppsEvent("id4", "test.type", "message4"))).build())
      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 0 }
    }

    @Test
    fun `should fail to purge the audit queue`() {
      webTestClient.put()
        .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.auditQueueConfig().queueName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isNotFound
    }
  }

  @Nested
  inner class GetDlqMessages {
    val defaultMessageAttributes = MessageAttributes(EventType("test.type", "String"))
    val defaultEvent = HmppsEvent("event-id", "test.type", "event-contents")
    fun testMessage(id: String) = Message(gsonString(defaultEvent), "message-$id", defaultMessageAttributes)

    @Test
    fun `requires a valid authentication token`() {
      webTestClient.get()
        .uri("/queue-admin/get-dlq-messages/any-queue")
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `requires the correct role`() {
      webTestClient.get()
        .uri("/queue-admin/get-dlq-messages/any-queue")
        .headers { it.authToken(roles = listOf("WRONG_ROLE")) }
        .exchange()
        .expectStatus().isForbidden
    }

    @Test
    fun `should fail if dlq not found`() {
      webTestClient.get()
        .uri("/queue-admin/get-dlq-messages/UNKNOWN_DLQ")
        .headers { it.authToken() }
        .exchange()
        .expectStatus().isNotFound
    }

    @Test
    fun `should get all messages from the specified dlq`() {
      for (i in 1..3) {
        inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(gsonString(testMessage("id-$i"))).build())
      }
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 3 }

      webTestClient.get()
        .uri("/queue-admin/get-dlq-messages/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("messagesFoundCount").isEqualTo(3)
        .jsonPath("messagesReturnedCount").isEqualTo(3)
        .jsonPath("messages..body.MessageId").value(
          containsInAnyOrder(
            "message-id-1",
            "message-id-2",
            "message-id-3",
          ),
        )
    }

    @Test
    fun `should be able to specify the max number of returned messages`() {
      for (i in 1..20) {
        inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(gsonString(testMessage("id-$i"))).build())
      }
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 20 }

      webTestClient.get()
        .uri("/queue-admin/get-dlq-messages/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}?maxMessages=12")
        .headers { it.authToken() }
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("messagesFoundCount").isEqualTo(20)
        .jsonPath("messagesReturnedCount").isEqualTo(12)
        .jsonPath("$..messages.length()").isEqualTo(12)
    }
  }

  @Nested
  inner class OpenApiDocs {
    @Test
    fun `should show the reactive API in the Open API docs`() {
      webTestClient.get()
        .uri("/v3/api-docs")
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.paths['/queue-admin/retry-dlq/{dlqName}'].put.tags[0]").isEqualTo("hmpps-reactive-queue-resource")
    }
  }
}
