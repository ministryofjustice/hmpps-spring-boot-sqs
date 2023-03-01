package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.health

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.ReactiveHealthIndicator
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Adds version data to the /health endpoint. This is called by the UI to display API details
 */
@Component
class HealthInfo(buildProperties: BuildProperties) : ReactiveHealthIndicator {
  private val version: String = buildProperties.version

  override fun health(): Mono<Health> = Mono.just(Health.up().withDetail("version", version).build())
}
