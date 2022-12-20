package uk.gov.justice.hmpps.sqs

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.applicationinsights.TelemetryClient
import io.awspring.cloud.sqs.config.SqsBootstrapConfiguration
import io.awspring.cloud.sqs.config.SqsListenerConfigurer
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.REACTIVE
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import

@Configuration
@EnableConfigurationProperties(HmppsSqsProperties::class)
@AutoConfigureBefore(WebFluxAutoConfiguration::class, WebMvcAutoConfiguration::class)
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
  fun hmppsQueueService(
    telemetryClient: TelemetryClient?,
    hmppsTopicFactory: HmppsTopicFactory,
    hmppsQueueFactory: HmppsQueueFactory,
    hmppsSqsProperties: HmppsSqsProperties,
  ) = HmppsQueueService(telemetryClient, hmppsTopicFactory, hmppsQueueFactory, hmppsSqsProperties)

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = SERVLET)
  fun hmppsQueueResource(hmppsQueueService: HmppsQueueService) = HmppsQueueResource(hmppsQueueService)

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = REACTIVE)
  fun hmppsReactiveQueueResource(hmppsQueueService: HmppsQueueService) = HmppsReactiveQueueResource(hmppsQueueService)

  @Bean
  @ConditionalOnMissingBean
  @DependsOn("hmppsQueueService")
  fun hmppsQueueContainerFactoryProxy(
    factories: List<HmppsQueueDestinationContainerFactory>,
    hmppsSqsProperties: HmppsSqsProperties,
  ) = HmppsQueueSqsListenerContainerFactory(factories, hmppsSqsProperties)

  @Bean
  fun configurer(objectMapper: ObjectMapper): SqsListenerConfigurer = SqsListenerConfigurer { it.objectMapper = objectMapper }
}
