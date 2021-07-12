package uk.gov.justice.hmpps.sqs

import com.microsoft.applicationinsights.TelemetryClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.jms.annotation.EnableJms

@Configuration
@EnableConfigurationProperties(HmppsSqsProperties::class)
@EnableJms
class HmppsSqsConfiguration {

  @Bean
  fun hmppsTopicFactory(applicationContext: ConfigurableApplicationContext) = HmppsTopicFactory(applicationContext, AmazonSnsFactory())

  @Bean
  fun hmppsQueueFactory(applicationContext: ConfigurableApplicationContext) = HmppsQueueFactory(applicationContext, AmazonSqsFactory())

  @Bean
  fun hmppsQueueService(
    telemetryClient: TelemetryClient?,
    hmppsTopicFactory: HmppsTopicFactory,
    hmppsQueueFactory: HmppsQueueFactory,
    hmppsSqsProperties: HmppsSqsProperties,
  ) = HmppsQueueService(telemetryClient, hmppsTopicFactory, hmppsQueueFactory, hmppsSqsProperties)

  @Bean
  fun hmppsQueueResource(hmppsQueueService: HmppsQueueService) = HmppsQueueResource(hmppsQueueService)

  @Bean
  @DependsOn("hmppsQueueService")
  fun hmppsQueueContainerFactoryProxy(factories: List<HmppsQueueDestinationContainerFactory>) = HmppsQueueJmsListenerContainerFactory(factories)
}
