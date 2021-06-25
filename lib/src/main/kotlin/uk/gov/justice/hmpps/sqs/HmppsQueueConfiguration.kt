package uk.gov.justice.hmpps.sqs

import com.microsoft.applicationinsights.TelemetryClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(HmppsQueueProperties::class)
class HmppsQueueConfiguration {

  @Bean
  fun hmppsQueueService(
    telemetryClient: TelemetryClient?,
    applicationContext: ConfigurableApplicationContext,
    hmppsQueueProperties: HmppsQueueProperties,
  ) = HmppsQueueService(telemetryClient, HmppsQueueFactory(applicationContext, AmazonSqsFactory()), hmppsQueueProperties)

  @Bean
  fun hmppsQueueResource(hmppsQueueService: HmppsQueueService) = HmppsQueueResource(hmppsQueueService)
}
