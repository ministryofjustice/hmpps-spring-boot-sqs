package uk.gov.justice.digital.hmpps.templatepackagename.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uk.gov.justice.hmpps.kotlin.auth.dsl.ResourceServerConfigurationCustomizer

/**
 * The security filter chain itself is auto-configured by `hmpps-kotlin-spring-boot-starter`'s
 * `HmppsResourceServerConfiguration`. We only need to extend its default unauthorised-request-paths
 * to allow `/queue-admin/retry-all-dlqs` to be called without authentication.
 */
@Configuration
class ResourceServerConfiguration {
  @Bean
  fun resourceServerCustomizer() = ResourceServerConfigurationCustomizer {
    unauthorizedRequestPaths {
      addPaths = setOf("/queue-admin/retry-all-dlqs")
    }
  }
}
