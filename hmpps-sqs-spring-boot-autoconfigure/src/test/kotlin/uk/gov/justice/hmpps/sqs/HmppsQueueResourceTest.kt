package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.model.Message
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(HmppsQueueResource::class)
@AutoConfigureMockMvc(addFilters = false)
class HmppsQueueResourceTest {

  @Autowired
  private lateinit var mockMvc: MockMvc

  @MockBean
  private lateinit var hmppsQueueService: HmppsQueueService

  @Nested
  inner class RetryDlq {
    @Test
    fun `should call the service`() {
      val hmppsQueue = mock<HmppsQueue>()
      whenever(hmppsQueueService.findByDlqName("some dlq name"))
        .thenReturn(hmppsQueue)
      whenever(hmppsQueueService.retryDlqMessages(any()))
        .thenReturn(RetryDlqResult(2, listOf(Message())))

      mockMvc.perform(put("/queue-admin/retry-dlq/some dlq name"))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.messagesFoundCount").value(2))
        .andExpect(jsonPath("$.messages.length()").value(1))

      verify(hmppsQueueService).retryDlqMessages(check { it.hmppsQueue === hmppsQueue })
    }

    @Test
    fun `should return not found`() {
      whenever(hmppsQueueService.findByDlqName("some dlq name")).thenReturn(null)

      mockMvc.perform(put("/queue-admin/retry-dlq/some dlq name"))
        .andExpect(status().isNotFound)

      verify(hmppsQueueService, times(0)).retryDlqMessages(any())
    }
  }

  @Nested
  inner class RetryAllDlqs {
    @Test
    fun `should call the service`() {
      whenever(hmppsQueueService.retryAllDlqs()).thenReturn(listOf())

      mockMvc.perform(put("/queue-admin/retry-all-dlqs"))
        .andExpect(status().isOk)

      verify(hmppsQueueService).retryAllDlqs()
    }
  }

  @Nested
  inner class PurgeQueue {
    @Test
    fun `should attempt to purge queue if found`() {
      whenever(hmppsQueueService.findQueueToPurge("some queue"))
        .thenReturn(PurgeQueueRequest("some queue", mock(), "some queue url"))
      whenever(hmppsQueueService.purgeQueue(any()))
        .thenReturn(PurgeQueueResult(10))

      mockMvc.perform(put("/queue-admin/purge-queue/some queue"))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.messagesFoundCount").value(10))

      verify(hmppsQueueService).findQueueToPurge("some queue")
      verify(hmppsQueueService).purgeQueue(
        check {
          assertThat(it.queueName).isEqualTo("some queue")
        },
      )
    }

    @Test
    fun `should return not found`() {
      whenever(hmppsQueueService.findQueueToPurge(anyString())).thenReturn(null)

      mockMvc.perform(put("/queue-admin/purge-queue/some queue"))
        .andExpect(status().isNotFound)

      verify(hmppsQueueService, times(0)).purgeQueue(any())
    }
  }
}

@SpringBootApplication
class HmppsQueueResourceTestApplication

fun main(args: Array<String>) {
  runApplication<HmppsQueueResourceTestApplication>(*args)
}
