package uk.gov.justice.hmpps.sqs

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.check
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
      val transferMessagesResult = mock<RetryDlqResult>()
      whenever(hmppsQueueService.findByDlqName("some dlq name")).thenReturn(hmppsQueue)
      whenever(hmppsQueueService.retryDlqMessages(any())).thenReturn(transferMessagesResult)

      mockMvc.perform(put("/queue-admin/retry-dlq/some dlq name"))
        .andExpect(status().isOk)

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
}

@SpringBootApplication
class HmppsQueueResourceTestApplication

fun main(args: Array<String>) {
  runApplication<HmppsQueueResourceTestApplication>(*args)
}
