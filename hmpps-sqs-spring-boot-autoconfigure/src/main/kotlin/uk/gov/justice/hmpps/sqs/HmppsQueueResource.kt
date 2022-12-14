package uk.gov.justice.hmpps.sqs

import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/queue-admin")
@ConditionalOnExpression("'\${hmpps.sqs.reactiveApi:false}'.equals('false')")
class HmppsQueueResource(hmppsQueueService: HmppsQueueService) {
  private val hmppsReactiveQueueResource = HmppsReactiveQueueResource(hmppsQueueService)

  @PutMapping("/retry-dlq/{dlqName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  fun retryDlq(@PathVariable("dlqName") dlqName: String) = runBlocking {
    hmppsReactiveQueueResource.retryDlq(dlqName)
  }

  /*
   * This endpoint is not secured because it should only be called from inside the Kubernetes service.
   * See test-app/src/main/kotlin/uk/gov/justice/digital/hmpps/hmppstemplatepackagename/config/ResourceServerConfiguration.kt for Spring Security config.
   * See test-app/helm_deploy/hmpps-template-kotlin/example/housekeeping-cronjob.yaml and ingress.yaml for Kubernetes config.
   */
  @PutMapping("/retry-all-dlqs")
  fun retryAllDlqs() = runBlocking {
    hmppsReactiveQueueResource.retryAllDlqs()
  }

  @PutMapping("/purge-queue/{queueName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  fun purgeQueue(@PathVariable("queueName") queueName: String) = runBlocking {
    hmppsReactiveQueueResource.purgeQueue(queueName)
  }

  /*
    Note: Once the DLQ messages have been read, they are not visible again (for subsequent reads) for approximately 30 seconds. This is due to the visibility
    timeout period which supports deleting of dlq messages when sent back to the processing queue
   */
  @GetMapping("/get-dlq-messages/{dlqName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  fun getDlqMessages(@PathVariable("dlqName") dlqName: String, @RequestParam("maxMessages", required = false, defaultValue = "100") maxMessages: Int) = runBlocking {
    hmppsReactiveQueueResource.getDlqMessages(dlqName, maxMessages)
  }
}
