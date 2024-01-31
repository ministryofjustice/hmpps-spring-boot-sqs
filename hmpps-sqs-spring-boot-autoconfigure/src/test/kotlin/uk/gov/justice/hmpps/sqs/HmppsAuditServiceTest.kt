package uk.gov.justice.hmpps.sqs

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
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

  private val hmppsQueue: HmppsQueue = mock()
  private val sqsAsyncClient: SqsAsyncClient = mock()

  @BeforeEach
  internal fun setup() {
    whenever(hmppsQueueService.findByQueueId(any())).thenReturn(hmppsQueue)
    whenever(hmppsQueue.sqsClient).thenReturn(sqsAsyncClient)
    whenever(hmppsQueue.queueUrl).thenReturn("a queue url")

    whenever(sqsAsyncClient.sendMessage(any<SendMessageRequest>()))
      .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()))
  }

  @Nested
  inner class PublishEventUsingDomainObject {

    @Test
    internal fun `test publish event using domain object`() = runTest {
      HmppsAuditService(
        hmppsQueueService = hmppsQueueService,
        objectMapper = objectMapper,
        applicationName = null,
        auditServiceName = null,
      )
        .publishEvent(HmppsAuditEvent(what = "bob", who = "me", service = "my-service"))

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

  @Nested
  inner class PublishEventUsingParameters {

    @Test
    internal fun `test publish event providing service as parameter`() = runTest {
      HmppsAuditService(
        hmppsQueueService = hmppsQueueService,
        objectMapper = objectMapper,
        applicationName = "application-name",
        auditServiceName = "service-name",
      )
        .publishEvent(what = "bob", who = "me", service = "my-service")

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

    @Test
    internal fun `test publish event defaulting to audit service name`() = runTest {
      HmppsAuditService(
        hmppsQueueService = hmppsQueueService,
        objectMapper = objectMapper,
        applicationName = "application-name",
        auditServiceName = "service-name",
      )
        .publishEvent(what = "bob", who = "me")

      verify(sqsAsyncClient).sendMessage(
        check<SendMessageRequest> {
          assertThat(it.queueUrl()).isEqualTo("a queue url")
          assertThat(it.messageBody())
            .contains(""""what":"bob"""")
            .contains(""""who":"me"""")
            .contains(""""service":"service-name"""")
        },
      )
    }

    @Test
    internal fun `test publish event defaulting to application name`() = runTest {
      HmppsAuditService(
        hmppsQueueService = hmppsQueueService,
        objectMapper = objectMapper,
        applicationName = "application-name",
        auditServiceName = null,
      )
        .publishEvent(what = "bob", who = "me")

      verify(sqsAsyncClient).sendMessage(
        check<SendMessageRequest> {
          assertThat(it.queueUrl()).isEqualTo("a queue url")
          assertThat(it.messageBody())
            .contains(""""what":"bob"""")
            .contains(""""who":"me"""")
            .contains(""""service":"application-name"""")
        },
      )
    }

    @Test
    internal fun `test publish event throws exception when service not defined`() = runTest {
      assertThrows<NullPointerException> {
        HmppsAuditService(
          hmppsQueueService = hmppsQueueService,
          objectMapper = objectMapper,
          applicationName = null,
          auditServiceName = null,
        )
          .publishEvent(what = "bob", who = "me")
      }

      verifyNoInteractions(sqsAsyncClient)
    }

    @Test
    internal fun `test publish event uses service parameter`() = runTest {
      HmppsAuditService(
        hmppsQueueService = hmppsQueueService,
        objectMapper = objectMapper,
        applicationName = null,
        auditServiceName = null,
      )
        .publishEvent(what = "bob", who = "me", service = "my-service")

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

    private suspend fun testAudit(
      expectedServiceValue: String,
      function: () -> SendMessageResponse,
    ) {
      whenever(sqsAsyncClient.sendMessage(any<SendMessageRequest>()))
        .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()))

      function()

      verify(sqsAsyncClient).sendMessage(
        check<SendMessageRequest> {
          assertThat(it.queueUrl()).isEqualTo("a queue url")
          assertThat(it.messageBody())
            .contains(""""what":"bob"""")
            .contains(""""who":"me"""")
            .contains(""""service":"$expectedServiceValue"""")
        },
      )
    }
  }
}
