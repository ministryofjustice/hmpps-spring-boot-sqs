package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.Message
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue

class TelemetryPropagationTest : IntegrationTestBase() {
  @BeforeEach
  fun setup() {
    GlobalOpenTelemetry.resetForTest()
    GlobalOpenTelemetry.set(OpenTelemetry.propagating(ContextPropagators.create(W3CTraceContextPropagator.getInstance())))
  }

  @Test
  fun `telemetry information is propagated between publishers and listeners`() = runTest {
    // Given a trace id
    Span.wrap(SpanContext.create("1234567890abcdef1234567890abcdef", "1234567890abcdef", TraceFlags.getDefault(), TraceState.getDefault()))
      .also { Context.current().with(it).makeCurrent() }

    // When I publish an OFFENDER_MOVEMENT-RECEPTION message
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).messageAttributes(
        mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
      ).build(),
    )

    // And the OFFENDER_MOVEMENT-RECEPTION message is consumed, resulting in an offender.movement.reception message being published
    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

    // Then the trace headers have been passed all the way through
    val message = objectMapper.readValue(outboundTestSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()).get().messages()[0].body(), Message::class.java)
    assertThat(message.MessageAttributes["traceparent"]?.Value).isEqualTo("00-1234567890abcdef1234567890abcdef-1234567890abcdef-00")
  }
}
