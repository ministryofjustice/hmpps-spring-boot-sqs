package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, proxyTargetClass = true)
class ResourceServerConfiguration {
  @Bean
  fun filterChain(http: HttpSecurity): SecurityFilterChain = http {
    headers { frameOptions { sameOrigin = true } }
    sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
    // Can't have CSRF protection as requires session
    csrf { disable() }
    authorizeHttpRequests {
      listOf(
        "/webjars/**",
        "/favicon.ico",
        "/health/**",
        "/info",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/queue-admin/retry-all-dlqs",
      ).forEach { authorize(it, permitAll) }
      authorize(anyRequest, authenticated)
    }
    oauth2ResourceServer { jwt { jwtAuthenticationConverter = AuthAwareTokenConverter() } }
  }.let { http.build() }
}
