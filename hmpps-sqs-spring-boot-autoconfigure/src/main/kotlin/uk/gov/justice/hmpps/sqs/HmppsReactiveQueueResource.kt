package uk.gov.justice.hmpps.sqs

import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/queue-admin")
class HmppsReactiveQueueResource(private val hmppsQueueService: HmppsQueueService) {

  @PutMapping("/retry-dlq/{dlqName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  suspend fun retryDlq(@PathVariable("dlqName") dlqName: String) = hmppsQueueService.findByDlqName(dlqName)
    ?.let { hmppsQueue -> hmppsQueueService.retryDlqMessages(RetryDlqRequest(hmppsQueue)) }
    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$dlqName not found")

  /*
   * By default, this endpoint is not secured because it should only be called from inside the Kubernetes service.
   * See test-app/src/main/kotlin/uk/gov/justice/digital/hmpps/hmppstemplatepackagename/config/ResourceServerConfiguration.kt for Spring Security config.
   * See https://github.com/ministryofjustice/hmpps-helm-charts/blob/main/charts/generic-service/templates/retry-dlq-cronjob.yaml and test-app/helm_deploy/hmpps-template-kotlin/example/ingress.yaml for Kubernetes config.
   */
  @PutMapping("/retry-all-dlqs")
  @PreAuthorize("@environment.containsProperty('hmpps.sqs.protectRetryAll') ? hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN')) : permitAll()")
  suspend fun retryAllDlqs() = hmppsQueueService.retryAllDlqs()

  /*
   * Note: Purge queue requests for the audit queue (id of audit) will be ignored.  This is because only the HMPPS Audit
   * should have the ability to purge its own queue.
   */
  @PutMapping("/purge-queue/{queueName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  suspend fun purgeQueue(@PathVariable("queueName") queueName: String) = hmppsQueueService.findQueueToPurge(queueName)
    ?.let { request -> hmppsQueueService.purgeQueue(request) }
    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$queueName not found")

  /*
    Note: Once the DLQ messages have been read, they are not visible again (for subsequent reads) for approximately 30 seconds. This is due to the visibility
    timeout period which supports deleting of dlq messages when sent back to the processing queue
   */
  @GetMapping("/get-dlq-messages/{dlqName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  suspend fun getDlqMessages(@PathVariable("dlqName") dlqName: String, @RequestParam("maxMessages", required = false, defaultValue = "100") maxMessages: Int) = hmppsQueueService.findByDlqName(dlqName)
    ?.let { hmppsQueue -> hmppsQueueService.getDlqMessages(GetDlqRequest(hmppsQueue, maxMessages)) }
    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$dlqName not found")

  /*
   * Solution 1 (selective retry): read-only/dry-run search of a DLQ's messages. Nothing is sent or deleted - this is
   * intended to let an engineer find the messageId(s) of interest (e.g. via a substring filter matching a business
   * entity ID in the message body) before calling retryDlqMessages below with those exact IDs.
   *
   * Note: as with getDlqMessages, messages read here are briefly invisible to other reads for ~1 second due to the
   * visibility timeout used while scanning.
   */
  @GetMapping("/search-dlq-messages/{dlqName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  suspend fun searchDlqMessages(
    @PathVariable("dlqName") dlqName: String,
    @RequestParam("filter", required = false) filter: String?,
    @RequestParam("maxMessages", required = false, defaultValue = "100") maxMessages: Int,
  ) = hmppsQueueService.findByDlqName(dlqName)
    ?.let { hmppsQueue -> hmppsQueueService.searchDlqMessages(SearchDlqRequest(hmppsQueue, filter, maxMessages)) }
    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$dlqName not found")

  /*
   * Solution 2 (selective retry): read-only/dry-run search of a DLQ for messages whose publish-time message
   * attribute (e.g. a correlation id attached by the producer) matches the given value. This is read-only -
   * matching messages are left on the DLQ. Once combined with retry-dlq-messages below, this lets an engineer find
   * and retry only the messages related to a particular failure, without needing to know SQS message IDs up front.
   */
  @GetMapping("/search-dlq-messages-by-attribute/{dlqName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  suspend fun searchDlqMessagesByAttribute(
    @PathVariable("dlqName") dlqName: String,
    @RequestParam("attributeName") attributeName: String,
    @RequestParam("attributeValue") attributeValue: String,
    @RequestParam("maxMessages", required = false, defaultValue = "100") maxMessages: Int,
  ) = hmppsQueueService.findByDlqName(dlqName)
    ?.let { hmppsQueue -> hmppsQueueService.searchDlqMessagesByAttribute(SearchDlqByAttributeRequest(hmppsQueue, attributeName, attributeValue, maxMessages)) }
    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$dlqName not found")

  /*
   * Solutions 1, 2 & 3 (selective retry): retry only the named messageIds from a DLQ, leaving every other message on
   * the DLQ untouched. Typically called with IDs obtained from searchDlqMessages/searchDlqMessagesByAttribute above,
   * or from the messageId logged in the enriched "sent-to-dlq" telemetry event (see HmppsErrorVisibilityHandler).
   */
  @PutMapping("/retry-dlq-messages/{dlqName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  suspend fun retryDlqMessages(
    @PathVariable("dlqName") dlqName: String,
    @RequestBody request: RetryDlqMessagesBody,
  ) = hmppsQueueService.findByDlqName(dlqName)
    ?.let { hmppsQueue -> hmppsQueueService.retryDlqMessagesByIds(RetryDlqMessagesRequest(hmppsQueue, request.messageIds)) }
    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$dlqName not found")
}

/** Request body for PUT /queue-admin/retry-dlq-messages/{dlqName} */
data class RetryDlqMessagesBody(val messageIds: List<String>)
