package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.nhaarman.mockitokotlin2.verify
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue

// TODO this test exists to demonstrate that tests using different Spring contexts use different queues and don't interfere with each other. It can be delete in due course.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["hmpps.sqs.region=eu-west-1"])
class QueueAdminResourceTest2 : IntegrationTestBase() {

  @Test
  fun `should transfer messages from DLQ to main queue and process them`() {
    sqsDlqClient.sendMessage(dlqUrl, "message3")
    sqsDlqClient.sendMessage(dlqUrl, "message4")
    await untilCallTo { sqsDlqClient.countMessagesOnQueue(dlqUrl) } matches { it == 2 }

    webTestClient.put()
      .uri("/queue-admin/retry-dlq/${sqsConfigProperties.dlqName}")
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    await untilCallTo { sqsDlqClient.countMessagesOnQueue(dlqUrl) } matches { it == 0 }
    await untilCallTo { sqsClient.countMessagesOnQueue(queueUrl) } matches { it == 0 }

    verify(messageServiceSpy).handleMessage("message3")
    verify(messageServiceSpy).handleMessage("message4")
  }
}
