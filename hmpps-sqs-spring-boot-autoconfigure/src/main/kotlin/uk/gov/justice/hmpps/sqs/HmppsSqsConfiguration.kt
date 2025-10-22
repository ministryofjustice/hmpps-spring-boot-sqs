package uk.gov.justice.hmpps.sqs

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.applicationinsights.TelemetryClient
import io.awspring.cloud.sqs.config.SqsBootstrapConfiguration
import io.awspring.cloud.sqs.config.SqsListenerConfigurer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.HealthContributor
import org.springframework.boot.actuate.health.HealthContributorRegistry
import org.springframework.boot.actuate.health.ReactiveHealthContributor
import org.springframework.boot.actuate.health.ReactiveHealthContributorRegistry
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.REACTIVE
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import
import org.springframework.core.type.AnnotatedTypeMetadata
import uk.gov.justice.hmpps.sqs.audit.HmppsAuditService

interface HmppsHealthContributorRegistry {
  fun registerContributor(name: String, contribute: () -> HealthContributor)
}

class HmppsBlockingHealthContributorRegistry(
  private val healthContributorRegistry: HealthContributorRegistry,
) : HmppsHealthContributorRegistry {
  override fun registerContributor(name: String, contribute: () -> HealthContributor) {
    if (healthContributorRegistry.getContributor(name) == null) {
      healthContributorRegistry.registerContributor(name, contribute())
    }
  }
}

class HmppsReactiveHealthContributorRepository(
  private val reactiveHealthContributorRegistry: ReactiveHealthContributorRegistry,
) : HmppsHealthContributorRegistry {
  override fun registerContributor(name: String, contribute: () -> HealthContributor) {
    if (reactiveHealthContributorRegistry.getContributor(name) == null) {
      reactiveHealthContributorRegistry.registerContributor(name, ReactiveHealthContributor.adapt(contribute()))
    }
  }
}

@Configuration
@EnableConfigurationProperties(HmppsSqsProperties::class)
@AutoConfigureBefore(WebFluxAutoConfiguration::class, WebMvcAutoConfiguration::class)
@Import(SqsBootstrapConfiguration::class)
class HmppsSqsConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = SERVLET)
  fun hmppsBlockingHealthContributorRegistry(healthContributorRegistry: HealthContributorRegistry) = HmppsBlockingHealthContributorRegistry(healthContributorRegistry)

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = REACTIVE)
  fun hmppsReactiveHealthContributorRepository(reactiveHealthContributorRegistry: ReactiveHealthContributorRegistry) = HmppsReactiveHealthContributorRepository(reactiveHealthContributorRegistry)

  @Bean
  @ConditionalOnMissingBean
  fun hmppsTopicFactory(applicationContext: ConfigurableApplicationContext, healthContributorRegistry: HmppsHealthContributorRegistry) = HmppsTopicFactory(applicationContext, healthContributorRegistry, SnsClientFactory())

  @Bean
  @ConditionalOnMissingBean
  fun hmppsQueueFactory(applicationContext: ConfigurableApplicationContext, healthContributorRegistry: HmppsHealthContributorRegistry, hmppsErrorTimeoutManager: HmppsErrorVisibilityHandler, objectMapper: ObjectMapper) = HmppsQueueFactory(applicationContext, healthContributorRegistry, SqsClientFactory(), hmppsErrorTimeoutManager, objectMapper)

  @Bean
  @ConditionalOnMissingBean
  fun hmppsErrorVisibilityHandler(objectMapper: ObjectMapper, hmppsSqsProperties: HmppsSqsProperties, telemetryClient: TelemetryClient?) = HmppsErrorVisibilityHandler(objectMapper, hmppsSqsProperties, telemetryClient)

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
  @Conditional(ConditionalOnNonAuditQueueDefinition::class)
  fun hmppsQueueResource(hmppsQueueService: HmppsQueueService) = HmppsQueueResource(hmppsQueueService)

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = REACTIVE)
  @Conditional(ConditionalOnNonAuditQueueDefinition::class)
  fun hmppsReactiveQueueResource(hmppsQueueService: HmppsQueueService) = HmppsReactiveQueueResource(hmppsQueueService)

  @Bean
  @ConditionalOnMissingBean
  @DependsOn("hmppsQueueService")
  fun hmppsQueueContainerFactoryProxy(
    factories: List<HmppsQueueDestinationContainerFactory>,
    hmppsSqsProperties: HmppsSqsProperties,
  ) = HmppsQueueSqsListenerContainerFactory(factories, hmppsSqsProperties)

  @Bean
  @ConditionalOnMissingBean
  @Conditional(ConditionalOnAuditQueueDefinition::class)
  fun hmppsAuditService(
    hmppsQueueService: HmppsQueueService,
    objectMapper: ObjectMapper,
    @Value("\${spring.application.name:}") applicationName: String?,
  ) = HmppsAuditService(hmppsQueueService, objectMapper, applicationName)

  @Bean
  fun configurer(objectMapper: ObjectMapper): SqsListenerConfigurer = SqsListenerConfigurer { it.objectMapper = objectMapper }
}

class ConditionalOnNonAuditQueueDefinition : Condition {
  override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
    val bean: HmppsSqsProperties? = Binder.get(context.environment)
      .bind("hmpps.sqs", HmppsSqsProperties::class.java).orElse(null)

    // true if we find a queue without an id of 'audit'
    return bean?.queues?.filterKeys { it != AUDIT_ID }?.isNotEmpty() ?: false
  }
}

class ConditionalOnAuditQueueDefinition : Condition {
  override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
    val bean: HmppsSqsProperties? = Binder.get(context.environment)
      .bind("hmpps.sqs", HmppsSqsProperties::class.java).orElse(null)

    // true if we find a queue with an id of audit
    return bean?.queues?.filterKeys { it == AUDIT_ID }?.isNotEmpty() ?: false
  }
}
