package uk.gov.justice.hmpps.sqs

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/queue-admin")
class HmppsSqsResource(private val hmppsQueueService: HmppsQueueService) {

  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }

  @PutMapping("/retry-dlq/{dlqName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  fun retryDlq(@PathVariable("dlqName") dlqName: String) =
    hmppsQueueService.findByDlqName(dlqName)
      ?.let { hmppsQueue -> hmppsQueueService.retryDlqMessages(RetryDlqRequest(hmppsQueue)) }
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$dlqName not found")

  /*
   * This endpoint is not secured because it should only be called from inside the Kubernetes service.
   * See test-app/src/main/kotlin/uk/gov/justice/digital/hmpps/hmppstemplatepackagename/config/ResourceServerConfiguration.kt for Spring Security config.
   * See test-app/helm_deploy/hmpps-template-kotlin/example/housekeeping-cronjob.yaml and ingress.yaml for Kubernetes config.
   */
  @PutMapping("/retry-all-dlqs")
  fun retryAllDlqs() = hmppsQueueService.retryAllDlqs()

  @PutMapping("/purge-queue/{queueName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  fun purgeQueue(@PathVariable("queueName") queueName: String) =
    hmppsQueueService.findQueueToPurge(queueName)
      ?.let { request -> hmppsQueueService.purgeQueue(request) }
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "$queueName not found")
}
