package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.springframework.http.MediaType
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.EventType
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.Message
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.MessageAttributes

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

  @Test
  fun `Can verify usage of spy bean for retry-dlq endpoint`() {
    val event = HmppsEvent("id", "test.type", "message1")
    val message = Message(gsonString(event), "message-id", MessageAttributes(EventType("test.type", "String")))
    outboundSqsDlqClientSpy.sendMessage(outboundDlqUrl, gsonString(message))
    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 1 }

    webTestClient.put()
      .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 0 }
    await untilCallTo { outboundSqsClientSpy.countMessagesOnQueue(outboundQueueUrl) } matches { it == 0 }

    verify(outboundMessageServiceSpy).handleMessage(event)
    verify(outboundSqsDlqClientSpy).receiveMessage(ReceiveMessageRequest(outboundDlqUrl).withMaxNumberOfMessages(1))
    verify(outboundSqsClientSpy).sendMessage(eq(outboundQueueUrl), anyString())
    verify(outboundSqsDlqClientSpy).deleteMessage(any())
  }

  @Test
  fun `Can verify usage of spy bean for purge-queue endpoint`() {
    outboundSqsDlqClientSpy.sendMessage(outboundDlqUrl, gsonString(HmppsEvent("id", "test.type", "message1")))
    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 1 }

    webTestClient.put()
      .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 0 }

    // One of these was in the @BeforeEach!
    verify(outboundSqsDlqClientSpy, times(2)).purgeQueue(any())
  }
}
