package uk.gov.justice.hmpps.sqs

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class HmppsQueueConfiguration {

  @Bean
  fun hmppsQueueService() = HmppsQueueService()

  @Bean
  fun hmppsQueueResource(hmppsQueueService: HmppsQueueService) = HmppsQueueResource(hmppsQueueService)

  @Bean
  fun hmppsQueueAdminConfig() = HmppsQueueAdminConfig()
}
