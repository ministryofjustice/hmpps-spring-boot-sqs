package uk.gov.justice.hmpps.sqs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.reactive.server.WebTestClient

@WebMvcTest(HmppsAsyncQueueResource::class)
@AutoConfigureMockMvc(addFilters = false)
class HmppsAsyncQueueResourceTest {

  @Autowired
  private lateinit var webTestClient: WebTestClient

  @MockBean
  private lateinit var hmppsQueueService: HmppsQueueService

  @MockBean
  private lateinit var hmppsAsyncQueueService: HmppsAsyncQueueService

  @Nested
  inner class RetryDlq {
    @Test
    fun `should call the service for a sync queue client`() = runBlocking<Unit> {
      val hmppsQueue = mock<HmppsQueue>()
      whenever(hmppsQueueService.findByDlqName("some dlq name"))
        .thenReturn(hmppsQueue)
      whenever(hmppsQueueService.retryDlqMessages(any()))
        .thenReturn(RetryDlqResult(2, listOf(DlqMessage(mapOf("key" to "value"), "id"))))

      webTestClient.put()
        .uri("/queue-admin/retry-dlq/some dlq name")
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.messagesFoundCount").isEqualTo(2)
        .jsonPath("$.messages.length()").isEqualTo(1)
        .jsonPath("$.messages[0].messageId").isEqualTo("id")
        .jsonPath("$.messages[0].body.key").isEqualTo("value")

      verify(hmppsQueueService).retryDlqMessages(check { it.hmppsQueue === hmppsQueue })
    }

    @Test
    fun `should call the service for an async queue client`() = runBlocking<Unit> {
      val hmppsAsyncQueue = mock<HmppsAsyncQueue>()
      whenever(hmppsQueueService.findByDlqName("some dlq name"))
        .thenReturn(null)
      whenever(hmppsAsyncQueueService.findByDlqName("some dlq name"))
        .thenReturn(hmppsAsyncQueue)
      whenever(hmppsAsyncQueueService.retryDlqMessages(any())).doSuspendableAnswer {
        withContext(Dispatchers.Default) { RetryDlqResult(2, listOf(DlqMessage(mapOf("key" to "value"), "id"))) }
      }

      webTestClient.put()
        .uri("/queue-admin/retry-dlq/some dlq name")
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.messagesFoundCount").isEqualTo(2)
        .jsonPath("$.messages.length()").isEqualTo(1)
        .jsonPath("$.messages[0].messageId").isEqualTo("id")
        .jsonPath("$.messages[0].body.key").isEqualTo("value")

      verify(hmppsQueueService, never()).retryDlqMessages(any())
      verify(hmppsAsyncQueueService).retryDlqMessages(RetryAsyncDlqRequest(hmppsAsyncQueue))
    }

    @Test
    fun `should return not found`() = runBlocking<Unit> {
      whenever(hmppsQueueService.findByDlqName("some dlq name")).thenReturn(null)
      whenever(hmppsAsyncQueueService.findByDlqName("some dlq name")).thenReturn(null)

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
      whenever(hmppsAsyncQueueService.retryAllDlqs()).thenReturn(listOf())

      webTestClient.put()
        .uri("/queue-admin/retry-all-dlqs")
        .exchange()
        .expectStatus().isOk

      verify(hmppsAsyncQueueService).retryAllDlqs()
    }
  }

  @Nested
  inner class PurgeQueue {
    @Test
    fun `should attempt to purge with sync queue client`() = runBlocking<Unit> {
      whenever(hmppsQueueService.findQueueToPurge("some queue"))
        .thenReturn(PurgeQueueRequest("some queue", mock(), "some queue url"))
      whenever(hmppsQueueService.purgeQueue(any())).thenReturn(PurgeQueueResult(10))

      webTestClient.put()
        .uri("/queue-admin/purge-queue/some queue")
        .exchange()
        .expectStatus().isOk
        .expectBody().jsonPath("$.messagesFoundCount").isEqualTo(10)

      verify(hmppsQueueService).findQueueToPurge("some queue")
      verify(hmppsAsyncQueueService, never()).findQueueToPurge("some queue")
      verify(hmppsQueueService).purgeQueue(
        check {
          assertThat(it.queueName).isEqualTo("some queue")
        }
      )
    }

    @Test
    fun `should attempt to purge with async queue client`() = runBlocking<Unit> {
      whenever(hmppsQueueService.findQueueToPurge("some queue"))
        .thenReturn(null)
      whenever(hmppsAsyncQueueService.findQueueToPurge("some queue"))
        .thenReturn(PurgeAsyncQueueRequest("some queue", mock(), "some queue url"))
      whenever(hmppsAsyncQueueService.purgeQueue(any())).doSuspendableAnswer {
        withContext(Dispatchers.Default) { PurgeQueueResult(10) }
      }

      webTestClient.put()
        .uri("/queue-admin/purge-queue/some queue")
        .exchange()
        .expectStatus().isOk
        .expectBody().jsonPath("$.messagesFoundCount").isEqualTo(10)

      verify(hmppsQueueService).findQueueToPurge("some queue")
      verify(hmppsAsyncQueueService).findQueueToPurge("some queue")
      verify(hmppsAsyncQueueService).purgeQueue(
        check {
          assertThat(it.queueName).isEqualTo("some queue")
        }
      )
    }

    @Test
    fun `should return not found`() = runBlocking<Unit> {
      whenever(hmppsQueueService.findQueueToPurge("some queue"))
        .thenReturn(null)
      whenever(hmppsAsyncQueueService.findQueueToPurge("some queue"))
        .thenReturn(null)

      webTestClient.put()
        .uri("/queue-admin/purge-queue/some queue")
        .exchange()
        .expectStatus().isNotFound

      verify(hmppsQueueService).findQueueToPurge("some queue")
      verify(hmppsAsyncQueueService).findQueueToPurge("some queue")
    }
  }

  @Nested
  inner class GetDlq {
    @Test
    fun `should get dlq messages with sync queue client`() = runBlocking<Unit> {
      val hmppsQueue = mock<HmppsQueue>()
      whenever(hmppsQueueService.findByDlqName("some dlq"))
        .thenReturn(hmppsQueue)
      whenever(hmppsQueueService.getDlqMessages(any()))
        .thenReturn(GetDlqResult(2, 1, listOf(DlqMessage(messageId = "id", body = mapOf("key" to "value")))))

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
      verify(hmppsAsyncQueueService, never()).findByDlqName("some dlq")
      verify(hmppsQueueService).getDlqMessages(GetDlqRequest(hmppsQueue, 100))
    }

    @Test
    fun `should get dlq messages with an async queue client`() = runBlocking<Unit> {
      val hmppsAsyncQueue = mock<HmppsAsyncQueue>()
      whenever(hmppsQueueService.findByDlqName("some dlq"))
        .thenReturn(null)
      whenever(hmppsAsyncQueueService.findByDlqName("some dlq"))
        .thenReturn(hmppsAsyncQueue)
      whenever(hmppsAsyncQueueService.getDlqMessages(any())).doSuspendableAnswer {
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
      verify(hmppsAsyncQueueService).findByDlqName("some dlq")
      verify(hmppsAsyncQueueService).getDlqMessages(GetAsyncDlqRequest(hmppsAsyncQueue, 100))
    }

    @Test
    fun `should return not found`() = runBlocking<Unit> {
      whenever(hmppsQueueService.findByDlqName("some dlq"))
        .thenReturn(null)
      whenever(hmppsAsyncQueueService.findByDlqName("some dlq"))
        .thenReturn(null)

      webTestClient.get()
        .uri("/queue-admin/get-dlq-messages/some queue")
        .exchange()
        .expectStatus().isNotFound

      verify(hmppsQueueService).findByDlqName("some queue")
      verify(hmppsAsyncQueueService).findByDlqName("some queue")
    }
  }
}
