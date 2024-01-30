package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.http.MediaType
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.EventType
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.Message
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.MessageAttributes
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue

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

    verify(outboundSqsClientSpy).getQueueAttributes(any<GetQueueAttributesRequest>())
  }

  @Test
  fun `Can verify usage of spy bean for retry-dlq endpoint`() = runTest {
    val event = HmppsEvent("id", "test.type", "message1")
    val message = Message(gsonString(event), "message-id", MessageAttributes(EventType("test.type", "String")))
    val messageAttributes = mutableMapOf("eventType" to software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder().dataType("String").stringValue("test value").build())
    outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(gsonString(message)).messageAttributes(messageAttributes).build())
    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 1 }

    webTestClient.put()
      .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 0 }
    await untilCallTo { outboundSqsClientSpy.countMessagesOnQueue(outboundQueueUrl).get() } matches { it == 0 }

    val captor = argumentCaptor<SendMessageRequest>()

    verify(outboundMessageServiceSpy).handleMessage(event)
    verify(outboundSqsDlqClientSpy).receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundDlqUrl).maxNumberOfMessages(1).messageAttributeNames("All").build())
    verify(outboundSqsDlqClientSpy).deleteMessage(any<DeleteMessageRequest>())

    verify(outboundSqsClientSpy).sendMessage(captor.capture())

    assertThat(captor.firstValue.queueUrl()).isEqualTo(outboundQueueUrl)
    assertThat(captor.firstValue.messageBody()).isEqualTo(gsonString(message))
    assertThat(captor.firstValue.messageAttributes()).isEqualTo(messageAttributes)
  }

  @Test
  fun `Can verify usage of spy bean for purge-queue endpoint`() = runTest {
    outboundSqsDlqClientSpy.sendMessage(SendMessageRequest.builder().queueUrl(outboundDlqUrl).messageBody(gsonString(HmppsEvent("id", "test.type", "message1"))).build())
    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 1 }

    webTestClient.put()
      .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.outboundQueueConfig().dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    await untilCallTo { outboundSqsDlqClientSpy.countMessagesOnQueue(outboundDlqUrl).get() } matches { it == 0 }

    // One of these was in the @BeforeEach!
    verify(outboundSqsDlqClientSpy, times(2)).purgeQueue(any<PurgeQueueRequest>())
  }
}
