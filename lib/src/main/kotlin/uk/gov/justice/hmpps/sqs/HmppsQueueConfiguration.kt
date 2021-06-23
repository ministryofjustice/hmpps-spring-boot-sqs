package uk.gov.justice.hmpps.sqs

import com.microsoft.applicationinsights.TelemetryClient
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class HmppsQueueConfiguration {

  @Bean
  fun hmppsQueueService(telemetryClient: TelemetryClient?, applicationContext: ConfigurableApplicationContext) = HmppsQueueService(telemetryClient, applicationContext)

  @Bean
  fun hmppsQueueResource(hmppsQueueService: HmppsQueueService) = HmppsQueueResource(hmppsQueueService)
}
