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
  fun hmppsQueueFactory(applicationContext: ConfigurableApplicationContext) = HmppsQueueFactory(applicationContext, AmazonSqsFactory())

  @Bean
  fun hmppsQueueService(
    telemetryClient: TelemetryClient?,
    hmppsQueueFactory: HmppsQueueFactory,
    hmppsQueueProperties: HmppsQueueProperties,
  ) = HmppsQueueService(telemetryClient, hmppsQueueFactory, hmppsQueueProperties)

  @Bean
  fun hmppsQueueResource(hmppsQueueService: HmppsQueueService) = HmppsQueueResource(hmppsQueueService)
}
