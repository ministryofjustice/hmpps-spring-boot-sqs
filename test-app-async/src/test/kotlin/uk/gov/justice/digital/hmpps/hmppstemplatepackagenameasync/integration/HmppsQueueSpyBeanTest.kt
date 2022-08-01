package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import com.amazonaws.services.sqs.model.MessageAttributeValue
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.amazonaws.services.sqs.model.SendMessageRequest
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.EventType
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.Message
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.MessageAttributes

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
    val messageAttributes = mutableMapOf("eventType" to MessageAttributeValue().withDataType("String").withStringValue("test value"))
    outboundSqsDlqClientSpy.sendMessage(SendMessageRequest().withQueueUrl(outboundDlqUrl).withMessageBody(gsonString(message)).withMessageAttributes(messageAttributes))
    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 1 }

    webTestClient.put()
      .uri("/queue-admin-async/retry-dlq/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 0 }
    await untilCallTo { outboundSqsClientSpy.countMessagesOnQueue(outboundQueueUrl) } matches { it == 0 }

    val captor = argumentCaptor<SendMessageRequest>()

    verify(outboundMessageServiceSpy).handleMessage(event)
    verify(outboundSqsDlqClientSpy).receiveMessage(ReceiveMessageRequest(outboundDlqUrl).withMaxNumberOfMessages(1).withMessageAttributeNames("All"))
    verify(outboundSqsDlqClientSpy).deleteMessage(any())

    verify(outboundSqsClientSpy).sendMessage(captor.capture())

    assertThat(captor.firstValue.queueUrl).isEqualTo(outboundQueueUrl)
    assertThat(captor.firstValue.messageBody).isEqualTo(gsonString(message))
    assertThat(captor.firstValue.messageAttributes).isEqualTo(messageAttributes)
  }

  @Test
  fun `Can verify usage of spy bean for purge-queue endpoint`() {
    outboundSqsDlqClientSpy.sendMessage(outboundDlqUrl, gsonString(HmppsEvent("id", "test.type", "message1")))
    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 1 }

    webTestClient.put()
      .uri("/queue-admin-async/purge-queue/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl) } matches { it == 0 }

    // One of these was in the @BeforeEach!
    verify(outboundSqsDlqClientSpy, times(2)).purgeQueue(any())
  }
}
