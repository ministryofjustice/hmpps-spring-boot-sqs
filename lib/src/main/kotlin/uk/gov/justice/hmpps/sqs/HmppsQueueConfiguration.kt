package uk.gov.justice.hmpps.sqs

import com.microsoft.applicationinsights.TelemetryClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class HmppsQueueConfiguration {

  @Bean
  fun hmppsQueueService(telemertyClient: TelemetryClient?) = HmppsQueueService(telemertyClient)

  @Bean
  fun hmppsQueueResource(hmppsQueueService: HmppsQueueService) = HmppsQueueResource(hmppsQueueService)
}
