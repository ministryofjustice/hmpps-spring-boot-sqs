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
class SqsQueueAdminResource(private val sqsQueueAdminService: SqsQueueAdminService, private val hmppsQueueService: HmppsQueueService) {

  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }

  @PutMapping("/retry-dlq/{dlqName}")
  fun retryDlq(@PathVariable("dlqName") dlqName: String) {
    log.info("Received request to /queue-admin/retry-dlq/$dlqName")
    val hmppsQueue = hmppsQueueService.findByDlqName(dlqName) ?: throw ResponseStatusException(
      HttpStatus.NOT_FOUND, "$dlqName not found"
    )
    val result = sqsQueueAdminService.retryDlqMessages(RetryDlqRequest(hmppsQueue))
    log.info("Found ${result.messagesFoundCount} messages, attempted to retry ${result.messages.size}")
  }
}
