package uk.gov.justice.hmpps.sqs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.reactive.server.WebTestClient

@WebMvcTest(HmppsReactiveQueueResource::class)
@AutoConfigureMockMvc(addFilters = false)
class HmppsReactiveQueueResourceTest {

  @Autowired
  private lateinit var webTestClient: WebTestClient

  @MockBean
  private lateinit var hmppsQueueService: HmppsQueueService

  @BeforeEach
  fun setup() = reset(hmppsQueueService)

  @Nested
  inner class RetryDlq {
    @Test
    fun `should call the service for an async queue client`() = runBlocking<Unit> {
      val hmppsAsyncQueue = mock<HmppsQueue>()
      whenever(hmppsQueueService.findByDlqName("some dlq name"))
        .thenReturn(hmppsAsyncQueue)
      whenever(hmppsQueueService.retryDlqMessages(any())).doSuspendableAnswer {
        withContext(Dispatchers.Default) { RetryDlqResult(2) }
      }

      webTestClient.put()
        .uri("/queue-admin/retry-dlq/some dlq name")
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.messagesFoundCount").isEqualTo(2)

      verify(hmppsQueueService).retryDlqMessages(RetryDlqRequest(hmppsAsyncQueue))
    }

    @Test
    fun `should return not found`() = runBlocking<Unit> {
      whenever(hmppsQueueService.findByDlqName("some dlq name")).thenReturn(null)
      whenever(hmppsQueueService.findByDlqName("some dlq name")).thenReturn(null)

      webTestClient.put()
        .uri("/queue-admin/retry-dlq/some dlq name")
        .exchange()
        .expectStatus().isNotFound

      verify(hmppsQueueService, times(0)).retryDlqMessages(any())
    }
  }

  @Nested
  inner class RetryAllDlqs {
    @Test
    fun `should call the service`() = runBlocking<Unit> {
      whenever(hmppsQueueService.retryAllDlqs()).thenReturn(listOf())
      whenever(hmppsQueueService.retryAllDlqs()).thenReturn(listOf())

      webTestClient.put()
        .uri("/queue-admin/retry-all-dlqs")
        .exchange()
        .expectStatus().isOk

      verify(hmppsQueueService).retryAllDlqs()
      verify(hmppsQueueService).retryAllDlqs()
    }
  }

  @Nested
  inner class PurgeQueue {
    @Test
    fun `should attempt to purge with async queue client`() = runBlocking<Unit> {
      whenever(hmppsQueueService.findQueueToPurge("some queue"))
        .thenReturn(null)
      whenever(hmppsQueueService.findQueueToPurge("some queue"))
        .thenReturn(PurgeQueueRequest("some queue", mock(), "some queue url"))
      whenever(hmppsQueueService.purgeQueue(any())).doSuspendableAnswer {
        withContext(Dispatchers.Default) { PurgeQueueResult(10) }
      }

      webTestClient.put()
        .uri("/queue-admin/purge-queue/some queue")
        .exchange()
        .expectStatus().isOk
        .expectBody().jsonPath("$.messagesFoundCount").isEqualTo(10)

      verify(hmppsQueueService).findQueueToPurge("some queue")
      verify(hmppsQueueService).findQueueToPurge("some queue")
      verify(hmppsQueueService).purgeQueue(
        check {
          assertThat(it.queueName).isEqualTo("some queue")
        },
      )
    }

    @Test
    fun `should return not found`() {
      whenever(hmppsQueueService.findQueueToPurge("some queue"))
        .thenReturn(null)
      whenever(hmppsQueueService.findQueueToPurge("some queue"))
        .thenReturn(null)

      webTestClient.put()
        .uri("/queue-admin/purge-queue/some queue")
        .exchange()
        .expectStatus().isNotFound

      verify(hmppsQueueService).findQueueToPurge("some queue")
      verify(hmppsQueueService).findQueueToPurge("some queue")
    }
  }

  @Nested
  inner class GetDlq {
    @Test
    fun `should get dlq messages with an async queue client`() = runBlocking<Unit> {
      val hmppsAsyncQueue = mock<HmppsQueue>()
      whenever(hmppsQueueService.findByDlqName("some dlq"))
        .thenReturn(null)
      whenever(hmppsQueueService.findByDlqName("some dlq"))
        .thenReturn(hmppsAsyncQueue)
      whenever(hmppsQueueService.getDlqMessages(any())).doSuspendableAnswer {
        withContext(Dispatchers.Default) { GetDlqResult(2, 1, listOf(DlqMessage(messageId = "id", body = mapOf("key" to "value")))) }
      }

      webTestClient.get()
        .uri("/queue-admin/get-dlq-messages/some dlq")
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.messagesFoundCount").isEqualTo(2)
        .jsonPath("$.messagesReturnedCount").isEqualTo(1)
        .jsonPath("$.messages[0].messageId").isEqualTo("id")
        .jsonPath("$.messages[0].body.key").isEqualTo("value")

      verify(hmppsQueueService).findByDlqName("some dlq")
      verify(hmppsQueueService).findByDlqName("some dlq")
      verify(hmppsQueueService).getDlqMessages(GetDlqRequest(hmppsAsyncQueue, 100))
    }

    @Test
    fun `should return not found`() {
      whenever(hmppsQueueService.findByDlqName("some dlq"))
        .thenReturn(null)
      whenever(hmppsQueueService.findByDlqName("some dlq"))
        .thenReturn(null)

      webTestClient.get()
        .uri("/queue-admin/get-dlq-messages/some queue")
        .exchange()
        .expectStatus().isNotFound

      verify(hmppsQueueService).findByDlqName("some queue")
      verify(hmppsQueueService).findByDlqName("some queue")
    }
  }
}
