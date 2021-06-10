package uk.gov.justice.hmpps.sqs

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class HmppsQueueConfiguration {

  @Bean
  fun sqsQueueAdminService() = HmppsQueueService()

  @Bean
  fun hmppsQueueService() = HmppsQueueService()

  @Bean
  fun sqsQueueAdminResource(hmppsQueueService: HmppsQueueService) = HmppsQueueResource(hmppsQueueService)
}
