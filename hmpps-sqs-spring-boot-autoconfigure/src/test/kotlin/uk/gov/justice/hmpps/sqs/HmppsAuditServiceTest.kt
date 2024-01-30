package uk.gov.justice.hmpps.sqs

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import uk.gov.justice.hmpps.sqs.audit.HmppsAuditEvent
import uk.gov.justice.hmpps.sqs.audit.HmppsAuditService
import java.util.concurrent.CompletableFuture

@JsonTest
internal class HmppsAuditServiceTest(@Autowired private val objectMapper: ObjectMapper) {
  private val hmppsQueueService: HmppsQueueService = mock()
  private val hmppsAuditService = HmppsAuditService(hmppsQueueService, objectMapper)

  private val hmppsQueue: HmppsQueue = mock()
  private val sqsAsyncClient: SqsAsyncClient = mock()

  @BeforeEach
  internal fun setup() {
    whenever(hmppsQueueService.findByQueueId(any())).thenReturn(hmppsQueue)
    whenever(hmppsQueue.sqsClient).thenReturn(sqsAsyncClient)
    whenever(hmppsQueue.queueUrl).thenReturn("a queue url")
  }

  @Test
  internal fun testPublishEvent() = runTest {
    whenever(sqsAsyncClient.sendMessage(any<SendMessageRequest>()))
      .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()))

    hmppsAuditService.publishEvent(HmppsAuditEvent(what = "bob", who = "me", service = "my-service"))

    verify(sqsAsyncClient).sendMessage(
      check<SendMessageRequest> {
        assertThat(it.queueUrl()).isEqualTo("a queue url")
        assertThat(it.messageBody())
          .contains(""""what":"bob"""")
          .contains(""""who":"me"""")
          .contains(""""service":"my-service"""")
      },
    )
  }
}