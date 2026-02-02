package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.test.context.TestPropertySource
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.hmpps.sqs.SnsMessage
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue

@TestPropertySource(properties = ["hmpps.sqs.topics.outboundtopic.propagateTracing=false"])
class TelemetryNoPropagationTest : IntegrationTestBase() {
  companion object {
    @RegisterExtension
    val openTelemetryExtension: OpenTelemetryExtension = OpenTelemetryExtension.create()
  }

  @Test
  fun `telemetry information is NOT propagated between publishers and listeners`() = runTest {
    // Given a span
    withSpan {
      // When I publish an OFFENDER_MOVEMENT-RECEPTION message
      val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
      inboundSnsClient.publish(
        PublishRequest.builder()
          .topicArn(inboundTopicArn)
          .message(jsonString(event))
          .messageAttributes(
            mapOf(
              "eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build(),
            ),
          )
          .build(),
      )
    }

    // And the OFFENDER_MOVEMENT-RECEPTION message is consumed, resulting in an offender.movement.reception message being published
    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

    // Then the trace headers have not been propagated
    val message = jsonMapper.readValue(outboundTestSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()).get().messages()[0].body(), SnsMessage::class.java)
    assertThat(message.messageAttributes["traceparent"]).isNull()
    // and that we have read the message attributes successfully
    assertThat(message.messageAttributes["eventType"].toString()).contains("offender.movement.reception")
  }

  private fun withSpan(block: () -> Unit): Span {
    val span = openTelemetryExtension.openTelemetry.getTracer("hmpps-sqs").spanBuilder("test-span").startSpan()
    Context.current().with(span).makeCurrent().use { block() }
    return span.also { it.end() }
  }
}
