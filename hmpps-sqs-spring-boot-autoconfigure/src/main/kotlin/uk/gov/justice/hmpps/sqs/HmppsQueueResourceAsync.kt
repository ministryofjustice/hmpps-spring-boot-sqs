package uk.gov.justice.hmpps.sqs

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/*
 * An asynchronous wrapper around HmppsQueueResource
 */
@RestController
@RequestMapping("/queue-admin-async")
class HmppsQueueResourceAsync(private val hmppsQueueResource: HmppsQueueResource) {

  @PutMapping("/retry-dlq/{dlqName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  suspend fun retryDlq(@PathVariable("dlqName") dlqName: String): Mono<RetryDlqResult> = Mono.defer { hmppsQueueResource.retryDlq(dlqName).let { Mono.just(it) } }

  @PutMapping("/retry-all-dlqs")
  suspend fun retryAllDlqs(): Flux<RetryDlqResult> = Flux.defer { hmppsQueueResource.retryAllDlqs().let { Flux.fromIterable(it) } }

  @PutMapping("/purge-queue/{queueName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  suspend fun purgeQueue(@PathVariable("queueName") queueName: String): Mono<PurgeQueueResult> = Mono.defer { hmppsQueueResource.purgeQueue(queueName).let { Mono.just(it) } }

  @GetMapping("/get-dlq-messages/{dlqName}")
  @PreAuthorize("hasRole(@environment.getProperty('hmpps.sqs.queueAdminRole', 'ROLE_QUEUE_ADMIN'))")
  suspend fun getDlqMessages(@PathVariable("dlqName") dlqName: String, @RequestParam("maxMessages", required = false, defaultValue = "100") maxMessages: Int): Mono<GetDlqResult> = Mono.defer { hmppsQueueResource.getDlqMessages(dlqName, maxMessages).let { Mono.just(it) } }
}
