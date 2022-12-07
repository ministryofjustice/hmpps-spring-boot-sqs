package uk.gov.justice.hmpps.sqs

import com.microsoft.applicationinsights.TelemetryClient
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.jms.annotation.EnableJms

@Configuration
@EnableConfigurationProperties(HmppsSqsProperties::class)
@EnableJms
@AutoConfigureBefore(HealthEndpointAutoConfiguration::class)
class HmppsSqsConfiguration {

  @Bean
  @ConditionalOnMissingBean
  fun hmppsTopicFactory(applicationContext: ConfigurableApplicationContext) = HmppsTopicFactory(applicationContext, SnsClientFactory())

  @Bean
  @ConditionalOnMissingBean
  fun hmppsAsyncTopicFactory(applicationContext: ConfigurableApplicationContext) = HmppsAsyncTopicFactory(applicationContext, SnsAsyncClientFactory())

  @Bean
  @ConditionalOnMissingBean
  fun hmppsQueueFactory(applicationContext: ConfigurableApplicationContext) = HmppsQueueFactory(applicationContext, SqsClientFactory())

  @Bean
  @ConditionalOnMissingBean
  fun hmppsAsyncQueueFactory(applicationContext: ConfigurableApplicationContext) = HmppsAsyncQueueFactory(applicationContext, SqsAsyncClientFactory())

  @Bean
  @ConditionalOnMissingBean
  fun hmppsQueueService(
    telemetryClient: TelemetryClient?,
    hmppsTopicFactory: HmppsTopicFactory,
    hmppsQueueFactory: HmppsQueueFactory,
    hmppsSqsProperties: HmppsSqsProperties,
  ) = HmppsQueueService(telemetryClient, hmppsTopicFactory, hmppsQueueFactory, hmppsSqsProperties)

  @Bean
  @ConditionalOnMissingBean
  fun hmppsAsyncQueueService(
    telemetryClient: TelemetryClient?,
    hmppsAsyncTopicFactory: HmppsAsyncTopicFactory,
    hmppsAsyncQueueFactory: HmppsAsyncQueueFactory,
    hmppsSqsProperties: HmppsSqsProperties,
  ) = HmppsAsyncQueueService(telemetryClient, hmppsAsyncTopicFactory, hmppsAsyncQueueFactory, hmppsSqsProperties)

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnExpression("'\${hmpps.sqs.reactiveApi:false}'.equals('false')")
  fun hmppsQueueResource(hmppsQueueService: HmppsQueueService, hmppsAsyncQueueService: HmppsAsyncQueueService) = HmppsQueueResource(hmppsQueueService, hmppsAsyncQueueService)

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnExpression("\${hmpps.sqs.reactiveApi:false}")
  fun hmppsQueueResourceAsync(hmppsQueueService: HmppsQueueService, hmppsAsyncQueueService: HmppsAsyncQueueService) = HmppsAsyncQueueResource(hmppsQueueService, hmppsAsyncQueueService)

  @Bean
  @ConditionalOnMissingBean
  @DependsOn("hmppsQueueService")
  fun hmppsQueueContainerFactoryProxy(factories: List<HmppsQueueDestinationContainerFactory>) = HmppsQueueJmsListenerContainerFactory(factories)
}
