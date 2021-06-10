package uk.gov.justice.hmpps.sqs

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/queue-admin")
class HmppsQueueResource(private val hmppsQueueService: HmppsQueueService) {

  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }

  @PutMapping("/retry-dlq/{dlqName}")
  fun retryDlq(@PathVariable("dlqName") dlqName: String) =
    hmppsQueueService.findByDlqName(dlqName)
      ?.let { hmppsQueue -> hmppsQueueService.retryDlqMessages(RetryDlqRequest(hmppsQueue)) }
      ?.also { retryDlqResult -> log.info("Found ${retryDlqResult.messagesFoundCount} messages, attempted to retry ${retryDlqResult.messages.size}") }
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$dlqName not found")
}