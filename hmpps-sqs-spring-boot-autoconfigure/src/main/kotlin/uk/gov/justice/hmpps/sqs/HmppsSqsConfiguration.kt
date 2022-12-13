package uk.gov.justice.hmpps.sqs

import com.microsoft.applicationinsights.TelemetryClient
import io.awspring.cloud.sqs.config.SqsBootstrapConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import

@Configuration
@EnableConfigurationProperties(HmppsSqsProperties::class)
@AutoConfigureBefore(WebFluxAutoConfiguration::class)
@Import(SqsBootstrapConfiguration::class)
class HmppsSqsConfiguration {

  @Bean
  @ConditionalOnMissingBean
  fun hmppsTopicFactory(applicationContext: ConfigurableApplicationContext) = HmppsTopicFactory(applicationContext, SnsClientFactory())

  @Bean
  @ConditionalOnMissingBean
  fun hmppsQueueFactory(applicationContext: ConfigurableApplicationContext) = HmppsQueueFactory(applicationContext, SqsClientFactory())

  @Bean
  @ConditionalOnMissingBean
  fun hmppsTopicService(hmppsTopicFactory: HmppsTopicFactory, hmppsSqsProperties: HmppsSqsProperties) =
    HmppsTopicService(hmppsTopicFactory, hmppsSqsProperties)

  @Bean
  @ConditionalOnMissingBean
  fun hmppsQueueService(
    telemetryClient: TelemetryClient?,
    hmppsTopicService: HmppsTopicService,
    hmppsQueueFactory: HmppsQueueFactory,
    hmppsSqsProperties: HmppsSqsProperties,
  ) = HmppsQueueService(telemetryClient, hmppsTopicService, hmppsQueueFactory, hmppsSqsProperties)

  @Bean
  @ConditionalOnMissingBean
  fun hmppsQueueResource(hmppsQueueService: HmppsQueueService) = HmppsQueueResource(hmppsQueueService)

  @Bean
  @ConditionalOnMissingBean
  @DependsOn("hmppsQueueService")
  fun hmppsQueueContainerFactoryProxy(
    factories: List<HmppsQueueDestinationContainerFactory>,
    hmppsSqsProperties: HmppsSqsProperties,
  ) = HmppsQueueSqsListenerContainerFactory(factories, hmppsSqsProperties)
}
