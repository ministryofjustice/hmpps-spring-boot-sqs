package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.mock.env.MockEnvironment

class HmppsSqsConfigurationTest {
  @Nested
  inner class ServletHmppsSqsResourceConfiguration {
    private val runner = WebApplicationContextRunner()
      .withConfiguration(
        UserConfigurations.of(
          JacksonAutoConfiguration::class.java,
          HealthEndpointAutoConfiguration::class.java,
          HmppsSqsConfiguration::class.java,
        ),
      )

    @Test
    internal fun `test no beans defined does not create resource bean`() {
      runner.run {
        assertThat(it).doesNotHaveBean("hmppsQueueResource")
      }
    }

    @Test
    internal fun `test audit queue defined does not create resource bean`() {
      runner.withPropertyValues(
        "hmpps.sqs.queues.audit.queueName=billyBob",
      ).run {
        assertThat(it).doesNotHaveBean("hmppsQueueResource")
      }
    }

    @Test
    internal fun `test non audit queue defined creates resource bean`() {
      runner.withPropertyValues(
        "hmpps.sqs.queues.queue.queueName=billyBob",
      ).run {
        assertThat(it).hasBean("hmppsQueueResource")
      }
    }

    @Test
    internal fun `test both queues defined creates resource bean`() {
      runner.withPropertyValues(
        "hmpps.sqs.queues.audit.queueName=billyBob",
        "hmpps.sqs.queues.queue2.queueName=johnSmith",
      ).run {
        assertThat(it).hasBean("hmppsQueueResource")
      }
    }
  }

  @Nested
  inner class ReactiveHmppsSqsResourceConfiguration {
    private val runner = ReactiveWebApplicationContextRunner()
      .withConfiguration(
        UserConfigurations.of(
          JacksonAutoConfiguration::class.java,
          EndpointAutoConfiguration::class.java,
          WebEndpointAutoConfiguration::class.java,
          HealthEndpointAutoConfiguration::class.java,
          HmppsSqsConfiguration::class.java,
        ),
      )

    @Test
    internal fun `test no beans defined does not create resource bean`() {
      runner.run {
        assertThat(it).doesNotHaveBean("hmppsReactiveQueueResource")
      }
    }

    @Test
    internal fun `test audit queue defined does not create resource bean`() {
      runner.withPropertyValues(
        "hmpps.sqs.queues.audit.queueName=billyBob",
      ).run {
        assertThat(it).doesNotHaveBean("hmppsReactiveQueueResource")
      }
    }

    @Test
    internal fun `test non audit queue defined creates resource bean`() {
      runner.withPropertyValues(
        "hmpps.sqs.queues.queue.queueName=billyBob",
      ).run {
        assertThat(it).hasBean("hmppsReactiveQueueResource")
      }
    }

    @Test
    internal fun `test both queues defined creates resource bean`() {
      runner.withPropertyValues(
        "hmpps.sqs.queues.audit.queueName=billyBob",
        "hmpps.sqs.queues.queue2.queueName=johnSmith",
      ).run {
        assertThat(it).hasBean("hmppsReactiveQueueResource")
      }
    }
  }

  @Nested
  inner class ConditionalOnNonAuditQueueDefinitionTest {
    private val context: ConditionContext = mock()
    private val metadata: AnnotatedTypeMetadata = mock()
    private val conditional = ConditionalOnNonAuditQueueDefinition()

    @Test
    internal fun `test no queues defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }
      assertThat(conditional.matches(context, metadata)).isFalse()
    }

    @Test
    internal fun `test audit queue defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }.apply {
        setProperty("hmpps.sqs.queues.audit.queueName", "SomeName")
      }
      assertThat(conditional.matches(context, metadata)).isFalse()
    }

    @Test
    internal fun `test non audit queue defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }.apply {
        setProperty("hmpps.sqs.queues.queue.queueName", "SomeName")
      }
      assertThat(conditional.matches(context, metadata)).isTrue()
    }

    @Test
    internal fun `test both audit and non audit queue defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }.apply {
        setProperty("hmpps.sqs.queues.audit.queueName", "AuditQueue")
        setProperty("hmpps.sqs.queues.queue2.queueName", "SomeName")
      }
      assertThat(conditional.matches(context, metadata)).isTrue()
    }
  }

  @Nested
  inner class ConditionalOnAuditQueueDefinitionTest {
    private val context: ConditionContext = mock()
    private val metadata: AnnotatedTypeMetadata = mock()
    private val conditional = ConditionalOnAuditQueueDefinition()

    @Test
    internal fun `test no queues defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }
      assertThat(conditional.matches(context, metadata)).isFalse()
    }

    @Test
    internal fun `test audit queue defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }.apply {
        setProperty("hmpps.sqs.queues.audit.queueName", "SomeName")
      }
      assertThat(conditional.matches(context, metadata)).isTrue()
    }

    @Test
    internal fun `test non audit queue defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }.apply {
        setProperty("hmpps.sqs.queues.queue.queueName", "SomeName")
      }
      assertThat(conditional.matches(context, metadata)).isFalse()
    }

    @Test
    internal fun `test both audit and non audit queue defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }.apply {
        setProperty("hmpps.sqs.queues.audit.queueName", "AuditQueue")
        setProperty("hmpps.sqs.queues.queue2.queueName", "SomeName")
      }
      assertThat(conditional.matches(context, metadata)).isTrue()
    }
  }
}
