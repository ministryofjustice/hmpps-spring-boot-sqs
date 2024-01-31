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
  inner class ServletHmppsSqsAuditQueueConfiguration {
    private val runner = WebApplicationContextRunner()
      .withConfiguration(
        UserConfigurations.of(
          JacksonAutoConfiguration::class.java,
          HealthEndpointAutoConfiguration::class.java,
          HmppsSqsConfiguration::class.java,
        ),
      )

    @Nested
    inner class HmppsAuditServiceBean {
      @Test
      fun `test no beans defined does not create audit service bean`() {
        runner.run {
          assertThat(it).doesNotHaveBean("hmppsAuditService")
        }
      }

      @Test
      fun `test audit queue defined creates audit service bean`() {
        runner.withPropertyValues(
          "hmpps.sqs.queues.audit.queueName=billyBob",
        ).run {
          assertThat(it).hasBean("hmppsAuditService")
        }
      }

      @Test
      fun `test non audit queue doesn't create audit service bean`() {
        runner.withPropertyValues(
          "hmpps.sqs.queues.queue.queueName=billyBob",
        ).run {
          assertThat(it).doesNotHaveBean("hmppsAuditService")
        }
      }

      @Test
      fun `test both queues defined creates audit service bean`() {
        runner.withPropertyValues(
          "hmpps.sqs.queues.audit.queueName=billyBob",
          "hmpps.sqs.queues.queue2.queueName=johnSmith",
        ).run {
          assertThat(it).hasBean("hmppsAuditService")
        }
      }
    }

    @Nested
    inner class HmppsAuditServiceServiceNameDefaultValue {
      @Test
      fun `test bean created default value of audit service name`() {
        runner.withPropertyValues(
          "spring.application.name=application-name",
          "audit.service.name=service-name",
          "hmpps.sqs.queues.audit.queueName=billyBob",
        ).run {
          assertThat(it).getBean("hmppsAuditService").hasFieldOrPropertyWithValue("auditServiceName", "service-name")
        }
      }

      @Test
      fun `test bean created default value of application name`() {
        runner.withPropertyValues(
          "spring.application.name=application-name",
          "hmpps.sqs.queues.audit.queueName=billyBob",
        ).run {
          assertThat(it).getBean("hmppsAuditService").hasFieldOrPropertyWithValue("auditServiceName", "application-name")
        }
      }

      @Test
      fun `test bean created with blank default value`() {
        runner.withPropertyValues(
          "hmpps.sqs.queues.audit.queueName=billyBob",
        ).run {
          assertThat(it).getBean("hmppsAuditService").hasFieldOrPropertyWithValue("auditServiceName", "")
        }
      }
    }
  }

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
    fun `test no beans defined does not create resource bean`() {
      runner.run {
        assertThat(it).doesNotHaveBean("hmppsQueueResource")
      }
    }

    @Test
    fun `test audit queue defined does not create resource bean`() {
      runner.withPropertyValues(
        "hmpps.sqs.queues.audit.queueName=billyBob",
      ).run {
        assertThat(it).doesNotHaveBean("hmppsQueueResource")
      }
    }

    @Test
    fun `test non audit queue defined creates resource bean`() {
      runner.withPropertyValues(
        "hmpps.sqs.queues.queue.queueName=billyBob",
      ).run {
        assertThat(it).hasBean("hmppsQueueResource")
      }
    }

    @Test
    fun `test both queues defined creates resource bean`() {
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
    fun `test no beans defined does not create resource bean`() {
      runner.run {
        assertThat(it).doesNotHaveBean("hmppsReactiveQueueResource")
      }
    }

    @Test
    fun `test audit queue defined does not create resource bean`() {
      runner.withPropertyValues(
        "hmpps.sqs.queues.audit.queueName=billyBob",
      ).run {
        assertThat(it).doesNotHaveBean("hmppsReactiveQueueResource")
      }
    }

    @Test
    fun `test non audit queue defined creates resource bean`() {
      runner.withPropertyValues(
        "hmpps.sqs.queues.queue.queueName=billyBob",
      ).run {
        assertThat(it).hasBean("hmppsReactiveQueueResource")
      }
    }

    @Test
    fun `test both queues defined creates resource bean`() {
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
    fun `test no queues defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }
      assertThat(conditional.matches(context, metadata)).isFalse()
    }

    @Test
    fun `test audit queue defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }.apply {
        setProperty("hmpps.sqs.queues.audit.queueName", "SomeName")
      }
      assertThat(conditional.matches(context, metadata)).isFalse()
    }

    @Test
    fun `test non audit queue defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }.apply {
        setProperty("hmpps.sqs.queues.queue.queueName", "SomeName")
      }
      assertThat(conditional.matches(context, metadata)).isTrue()
    }

    @Test
    fun `test both audit and non audit queue defined`() {
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
    fun `test no queues defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }
      assertThat(conditional.matches(context, metadata)).isFalse()
    }

    @Test
    fun `test audit queue defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }.apply {
        setProperty("hmpps.sqs.queues.audit.queueName", "SomeName")
      }
      assertThat(conditional.matches(context, metadata)).isTrue()
    }

    @Test
    fun `test non audit queue defined`() {
      MockEnvironment().also {
        whenever(context.environment).thenReturn(it)
      }.apply {
        setProperty("hmpps.sqs.queues.queue.queueName", "SomeName")
      }
      assertThat(conditional.matches(context, metadata)).isFalse()
    }

    @Test
    fun `test both audit and non audit queue defined`() {
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
