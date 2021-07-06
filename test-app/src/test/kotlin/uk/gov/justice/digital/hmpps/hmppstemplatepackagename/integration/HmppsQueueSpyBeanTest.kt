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

    verify(anotherSqsClientSpy).getQueueAttributes(any())
  }

  @Test
  fun `Can verify usage of spy bean for retry-dlq endpoint`() {
    anotherSqsDlqClientSpy.sendMessage(anotherDlqUrl, "message1")
    await untilCallTo { anotherSqsDlqClientSpy.countMessagesOnQueue(anotherDlqUrl) } matches { it == 1 }

    webTestClient.put()
      .uri("/queue-admin/retry-dlq/${hmppsSqsPropertiesSpy.anotherQueueConfig().dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    await untilCallTo { anotherSqsDlqClientSpy.countMessagesOnQueue(anotherDlqUrl) } matches { it == 0 }
    await untilCallTo { anotherSqsClientSpy.countMessagesOnQueue(anotherQueueUrl) } matches { it == 0 }

    verify(anotherMessageServiceSpy).handleMessage("message1")
    verify(anotherSqsDlqClientSpy).receiveMessage(ReceiveMessageRequest(anotherDlqUrl).withMaxNumberOfMessages(1))
    verify(anotherSqsClientSpy).sendMessage(eq(anotherQueueUrl), anyString())
    verify(anotherSqsDlqClientSpy).deleteMessage(any())
  }

  @Test
  fun `Can verify usage of spy bean for purge-queue endpoint`() {
    anotherSqsDlqClientSpy.sendMessage(anotherDlqUrl, "message1")
    await untilCallTo { anotherSqsDlqClientSpy.countMessagesOnQueue(anotherDlqUrl) } matches { it == 1 }

    webTestClient.put()
      .uri("/queue-admin/purge-queue/${hmppsSqsPropertiesSpy.anotherQueueConfig().dlqName}")
      .headers { it.authToken() }
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    await untilCallTo { anotherSqsDlqClientSpy.countMessagesOnQueue(anotherDlqUrl) } matches { it == 0 }

    // One of these was in the @BeforeEach!
    verify(anotherSqsDlqClientSpy, times(2)).purgeQueue(any())
  }
}
