package uk.gov.justice.digital.hmpps.templatepackagename.integration

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.verify
import org.springframework.http.MediaType
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.templatepackagename.service.HmppsEvent
import uk.gov.justice.hmpps.sqs.SnsMessage
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import java.time.Duration

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
      val message1 = SnsMessage(jsonString(event1), "message-id1", messageAttributesWithEventType("test.type"))
      val message2 = SnsMessage(jsonString(event2), "message-id2", messageAttributesWithEventType("test.type"))
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(message1)).build())
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(message2)).build())
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
      val message3 = SnsMessage(jsonString(event3), "message-id3", messageAttributesWithEventType("test.type"))
      val message4 = SnsMessage(jsonString(event4), "message-id4", messageAttributesWithEventType("test.type"))
      outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(jsonMapper.writeValueAsString(message3)).build())
      outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(jsonMapper.writeValueAsString(message4)).build())
      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 0 }
      await untilCallTo { outboundSqsClientSpy.countMessagesOnQueue(outboundQueueUrl).get() } matches { it == 0 }

      runTest {
        verify(outboundMessageServiceSpy).handleMessage(event3)
        verify(outboundMessageServiceSpy).handleMessage(event4)
      }
    }
  }

  @Nested
  inner class RetryAllDlqs {
    @Test
    fun `should transfer messages from DLQ to inbound queue and process them`() {
      val event5 = HmppsEvent("id5", "test.type", "message5")
      val event6 = HmppsEvent("id6", "test.type", "message6")
      val message5 = SnsMessage(jsonString(event5), "message-id5", messageAttributesWithEventType("test.type"))
      val message6 = SnsMessage(jsonString(event6), "message-id6", messageAttributesWithEventType("test.type"))
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(message5)).build())
      outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(jsonMapper.writeValueAsString(message6)).build())
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
        verify(outboundMessageServiceSpy).handleMessage(event6)
      }
    }
  }

  @Nested
  inner class PurgeQueue {
    @Test
    fun `should purge the inbound dlq`() {
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonString(HmppsEvent("id1", "test.type", "message1"))).build())
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonString(HmppsEvent("id2", "test.type", "message2"))).build())
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
      outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(jsonString(HmppsEvent("id3", "test.type", "message3"))).build())
      outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(jsonString(HmppsEvent("id4", "test.type", "message4"))).build())
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
    val defaultMessageAttributes = messageAttributesWithEventType("test.type")
    val defaultEvent = HmppsEvent("event-id", "test.type", "event-contents")
    fun testMessage(id: String) = SnsMessage(jsonString(defaultEvent), "message-$id", defaultMessageAttributes)

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
        inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(testMessage("id-$i"))).build())
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
        .jsonPath("messages..body.MessageId").value<List<String>> {
          assertThat(it).containsExactlyInAnyOrder(
            "message-id-1",
            "message-id-2",
            "message-id-3",
          )
        }
      // Ensure messages are quickly made visible again
      await.atMost(Duration.ofSeconds(3)) untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 3 }
    }

    @Test
    fun `should be able to specify the max number of returned messages`() {
      for (i in 1..20) {
        inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonString(testMessage("id-$i"))).build())
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
  inner class SearchDlqMessages {
    val defaultMessageAttributes = messageAttributesWithEventType("test.type")
    fun testMessage(id: String, contents: String) = SnsMessage(jsonString(HmppsEvent("event-$id", "test.type", contents)), "message-$id", defaultMessageAttributes)

    @Test
    fun `requires a valid authentication token`() {
      webTestClient.get()
        .uri("/queue-admin/search-dlq-messages/any-queue")
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `requires the correct role`() {
      webTestClient.get()
        .uri("/queue-admin/search-dlq-messages/any-queue")
        .headers { it.authToken(roles = listOf("WRONG_ROLE")) }
        .exchange()
        .expectStatus().isForbidden
    }

    @Test
    fun `should fail if dlq not found`() {
      webTestClient.get()
        .uri("/queue-admin/search-dlq-messages/UNKNOWN_DLQ")
        .headers { it.authToken() }
        .exchange()
        .expectStatus().isNotFound
    }

    @Test
    fun `should return only messages matching the filter, leaving all messages on the dlq`() {
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(testMessage("id-1", "prisoner A1234BC transferred"))).build())
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(testMessage("id-2", "prisoner Z9999ZZ transferred"))).build())
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(testMessage("id-3", "prisoner A1234BC released"))).build())
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 3 }

      webTestClient.get()
        .uri("/queue-admin/search-dlq-messages/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}?filter=A1234BC")
        .headers { it.authToken() }
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("messagesFoundCount").isEqualTo(3)
        .jsonPath("messagesReturnedCount").isEqualTo(2)
        .jsonPath("messages..body.MessageId").value<List<String>> {
          assertThat(it).containsExactlyInAnyOrder("message-id-1", "message-id-3")
        }

      // search is read-only / dry-run - nothing should have been removed from the dlq
      await.atMost(Duration.ofSeconds(3)) untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 3 }
    }

    @Test
    fun `should return all messages when no filter is supplied`() {
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(testMessage("id-1", "message one"))).build())
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(testMessage("id-2", "message two"))).build())
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 2 }

      webTestClient.get()
        .uri("/queue-admin/search-dlq-messages/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("messagesFoundCount").isEqualTo(2)
        .jsonPath("messagesReturnedCount").isEqualTo(2)
    }

    @Test
    fun `should return no messages when filter matches nothing`() {
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(testMessage("id-1", "message one"))).build())
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }

      webTestClient.get()
        .uri("/queue-admin/search-dlq-messages/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}?filter=NO_SUCH_MATCH")
        .headers { it.authToken() }
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("messagesFoundCount").isEqualTo(1)
        .jsonPath("messagesReturnedCount").isEqualTo(0)
    }
  }

  @Nested
  inner class RetryDlqMessagesById {
    @Test
    fun `requires a valid authentication token`() {
      webTestClient.put()
        .uri("/queue-admin/retry-dlq-messages/any-queue")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(mapOf("messageIds" to listOf("some-id")))
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `requires the correct role`() {
      webTestClient.put()
        .uri("/queue-admin/retry-dlq-messages/any-queue")
        .headers { it.authToken(roles = listOf("WRONG_ROLE")) }
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(mapOf("messageIds" to listOf("some-id")))
        .exchange()
        .expectStatus().isForbidden
    }

    @Test
    fun `should fail if dlq not found`() {
      webTestClient.put()
        .uri("/queue-admin/retry-dlq-messages/UNKNOWN_DLQ")
        .headers { it.authToken() }
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(mapOf("messageIds" to listOf("some-id")))
        .exchange()
        .expectStatus().isNotFound
    }

    @Test
    fun `should retry only the requested messages, leaving the rest on the dlq`() {
      val eventToRetry = HmppsEvent("id1", "test.type", "retry-me-marker")
      val eventToLeave = HmppsEvent("id2", "test.type", "leave me")
      val messageToRetry = SnsMessage(jsonString(eventToRetry), "message-to-retry", messageAttributesWithEventType("test.type"))
      val messageToLeave = SnsMessage(jsonString(eventToLeave), "message-to-leave", messageAttributesWithEventType("test.type"))
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(messageToRetry)).build())
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(messageToLeave)).build())
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 2 }

      // realistic workflow: search first to discover the actual SQS-assigned messageId of the one we want
      val searchResponse = webTestClient.get()
        .uri("/queue-admin/search-dlq-messages/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}?filter=retry-me-marker")
        .headers { it.authToken() }
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("messagesFoundCount").isEqualTo(2)
        .jsonPath("messagesReturnedCount").isEqualTo(1)
        .returnResult()
      val messageIdToRetry = jsonMapper.readTree(searchResponse.responseBody).at("/messages/0/messageId").asText()

      // let the messages read during search become visible again before retrying (same ~1s visibility timeout note as getDlqMessages)
      await.atMost(Duration.ofSeconds(3)) untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/retry-dlq-messages/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(mapOf("messageIds" to listOf(messageIdToRetry)))
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("messagesFoundCount").isEqualTo(2)
        .jsonPath("messagesRetriedCount").isEqualTo(1)
        .jsonPath("retriedMessageIds[0]").isEqualTo(messageIdToRetry)

      // the retried message should have been processed via the main queue...
      await untilCallTo { inboundSqsClient.countMessagesOnQueue(inboundQueueUrl).get() } matches { it == 0 }
      runTest {
        verify(inboundMessageServiceSpy).handleMessage(eventToRetry)
      }

      // ...while the other message is left untouched on the dlq
      await.atMost(Duration.ofSeconds(3)) untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }
    }

    @Test
    fun `should retry nothing and report zero when the requested message ids are not found`() {
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(jsonMapper.writeValueAsString(SnsMessage(jsonString(HmppsEvent("id1", "test.type", "contents")), "message-id1", messageAttributesWithEventType("test.type")))).build())
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }

      webTestClient.put()
        .uri("/queue-admin/retry-dlq-messages/${hmppsSqsPropertiesSpy.inboundQueueConfig().dlqName}")
        .headers { it.authToken() }
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(mapOf("messageIds" to listOf("no-such-message-id")))
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("messagesFoundCount").isEqualTo(1)
        .jsonPath("messagesRetriedCount").isEqualTo(0)

      await.atMost(Duration.ofSeconds(3)) untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }
    }
  }

  @Nested
  inner class OpenApiDocs {
    @Test
    fun `should show the non-reactive API in the Open API docs`() {
      webTestClient.get()
        .uri("/v3/api-docs")
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.paths['/queue-admin/retry-dlq/{dlqName}'].put.tags[0]").isEqualTo("hmpps-queue-resource")
    }
  }
}
