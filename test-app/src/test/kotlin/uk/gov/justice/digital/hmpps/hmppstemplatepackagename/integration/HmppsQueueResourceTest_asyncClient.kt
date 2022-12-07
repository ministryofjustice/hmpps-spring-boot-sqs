package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.hamcrest.Matchers
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.springframework.http.MediaType
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.EventType
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.Message
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.MessageAttributes

class HmppsQueueResourceTest_asyncClient : IntegrationTestBase() {

  @Nested
  inner class RetryDlq {
    @Test
    fun `should transfer messages from the async DLQ to the async queue`() {
      val event1 = HmppsEvent("id1", "test.type", "message3")
      val event2 = HmppsEvent("id2", "test.type", "message4")
      val message1 = Message(gsonString(event1), "message-id1", MessageAttributes(EventType("test.type", "String")))
      val message2 = Message(gsonString(event2), "message-id2", MessageAttributes(EventType("test.type", "String")))
      asyncSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(asyncDlqUrl).messageBody(gsonString(message1)).build())
      asyncSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(asyncDlqUrl).messageBody(gsonString(message2)).build())
      await untilCallTo { asyncSqsDlqClient.countMessagesOnQueue(asyncDlqUrl) } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.asyncQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { asyncSqsDlqClient.countMessagesOnQueue(asyncDlqUrl) } matches { it == 0 }
      await untilCallTo { asyncSqsClient.countMessagesOnQueue(asyncQueueUrl) } matches { it == 2 }
    }
  }

  @Nested
  inner class RetryAllDlqs {
    @Test
    fun `should transfer messages from async as well as normal queues`() {
      val event5 = HmppsEvent("id5", "test.type", "message5")
      val event6 = HmppsEvent("id6", "test.type", "message6")
      val message5 = Message(gsonString(event5), "message-id5", MessageAttributes(EventType("test.type", "String")))
      val message6 = Message(gsonString(event6), "message-id6", MessageAttributes(EventType("test.type", "String")))
      asyncSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(asyncDlqUrl).messageBody(gsonString(message5)).build())
      inboundSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundDlqUrl).messageBody(gsonString(message6)).build())
      await untilCallTo { asyncSqsDlqClient.countMessagesOnQueue(asyncDlqUrl) } matches { it == 1 }
      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl) } matches { it == 1 }

      webTestClient.put()
        .uri("/queue-admin/retry-all-dlqs")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { asyncSqsDlqClient.countMessagesOnQueue(asyncDlqUrl) } matches { it == 0 }
      await untilCallTo { asyncSqsClient.countMessagesOnQueue(asyncQueueUrl) } matches { it == 1 }

      await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl) } matches { it == 0 }
      await untilCallTo { inboundSqsClient.countMessagesOnQueue(inboundQueueUrl) } matches { it == 0 }
      verify(inboundMessageServiceSpy).handleMessage(event6)
    }
  }

  @Nested
  inner class PurgeQueue {
    @Test
    fun `should purge the async dlq`() {
      asyncSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(asyncDlqUrl).messageBody(gsonString(HmppsEvent("id1", "test.type", "message1"))).build())
      asyncSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(asyncDlqUrl).messageBody(gsonString(HmppsEvent("id2", "test.type", "message2"))).build())
      await untilCallTo { asyncSqsDlqClient.countMessagesOnQueue(asyncDlqUrl) } matches { it == 2 }

      webTestClient.put()
        .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.asyncQueueConfig().dlqName}")
        .headers { it.authToken() }
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk

      await untilCallTo { asyncSqsDlqClient.countMessagesOnQueue(asyncDlqUrl) } matches { it == 0 }
    }
  }

  @Nested
  inner class GetDlqMessages {
    val defaultMessageAttributes = MessageAttributes(EventType("test.type", "String"))
    val defaultEvent = HmppsEvent("event-id", "test.type", "event-contents")
    fun testMessage(id: String) = Message(gsonString(defaultEvent), "message-$id", defaultMessageAttributes)
    @Test
    fun `should get all messages from the async dlq`() {
      for (i in 1..3) {
        asyncSqsDlqClient.sendMessage(SendMessageRequest.builder().queueUrl(asyncDlqUrl).messageBody(gsonString(testMessage("id-$i"))).build())
      }
      await untilCallTo { asyncSqsDlqClient.countMessagesOnQueue(asyncDlqUrl) } matches { it == 3 }

      webTestClient.get()
        .uri("/queue-admin/get-dlq-messages/${hmppsSqsPropertiesSpy.asyncQueueConfig().dlqName}")
        .headers { it.authToken() }
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("messagesFoundCount").isEqualTo(3)
        .jsonPath("messagesReturnedCount").isEqualTo(3)
        .jsonPath("messages..body.MessageId").value(
          Matchers.contains(
            "message-id-1",
            "message-id-2",
            "message-id-3"
          )
        )
    }
  }
}
