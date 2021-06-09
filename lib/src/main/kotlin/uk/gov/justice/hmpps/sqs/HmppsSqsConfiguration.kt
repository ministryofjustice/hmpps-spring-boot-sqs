package uk.gov.justice.hmpps.sqs

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class HmppsSqsConfiguration {

  @Bean
  fun sqsQueueAdminService() = SqsQueueAdminService()

  @Bean
  fun hmppsQueueService() = HmppsQueueService()

  @Bean
  fun sqsQueueAdminResource(sqsQueueAdminService: SqsQueueAdminService, hmppsQueueService: HmppsQueueService) = SqsQueueAdminResource(sqsQueueAdminService, hmppsQueueService)
}
