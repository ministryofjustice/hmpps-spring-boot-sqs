package uk.gov.justice.hmpps.sqs

import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
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
   * This endpoint is not secured because it should only be called from inside the Kubernetes service.
   * See test-app/src/main/kotlin/uk/gov/justice/digital/hmpps/hmppstemplatepackagename/config/ResourceServerConfiguration.kt for Spring Security config.
   * See https://github.com/ministryofjustice/hmpps-helm-charts/blob/main/charts/generic-service/templates/retry-dlq-cronjob.yaml and test-app/helm_deploy/hmpps-template-kotlin/example/ingress.yaml for Kubernetes config.
   */
  @PutMapping("/retry-all-dlqs")
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
}
