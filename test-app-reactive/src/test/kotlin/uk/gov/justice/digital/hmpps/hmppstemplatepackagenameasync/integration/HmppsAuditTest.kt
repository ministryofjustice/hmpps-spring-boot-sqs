package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.HmppsEvent
import uk.gov.justice.hmpps.sqs.SnsMessage
import uk.gov.justice.hmpps.sqs.audit.HmppsAuditEvent
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import uk.gov.justice.hmpps.sqs.eventTypeMessageAttributes
import java.time.Instant

class HmppsAuditTest : IntegrationTestBase() {
  companion object {
    @RegisterExtension
    val openTelemetryExtension: OpenTelemetryExtension = OpenTelemetryExtension.create()
  }

  @Test
  fun `event is audited and calls service with domain object`() = runTest {
    val startTime = Instant.now()
    val event = HmppsEvent("audit-id", "OFFENDER_AUDIT-OBJECT", "some event contents")
    val message1 = SnsMessage(jsonString(event), "message-id1", messageAttributesWithEventType("OFFENDER_AUDIT-OBJECT"))
    inboundSqsClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundQueueUrl).messageBody(jsonString(message1)).build())

    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }
    await untilCallTo { auditSqsClient.countMessagesOnQueue(auditQueueUrl).get() } matches { it == 1 }

    val receivedEvent = jsonMapper.readValue(auditSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(auditQueueUrl).build()).get().messages()[0].body(), HmppsAuditEvent::class.java)

    assertThat(receivedEvent.who).isEqualTo("me")
    assertThat(receivedEvent.what).isEqualTo("important event")
    assertThat(receivedEvent.service).isEqualTo("my-special-test-app")
    assertThat(receivedEvent.`when`).isBetween(startTime, Instant.now())
  }

  @Test
  fun `event is audited and calls service with parameters`() = runTest {
    val startTime = Instant.now()
    val event = HmppsEvent("audit-id", "OFFENDER_AUDIT-PARAMETER", "some event contents")
    val message1 = SnsMessage(jsonString(event), "message-id1", messageAttributesWithEventType("OFFENDER_AUDIT-PARAMETER"))
    inboundSqsClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundQueueUrl).messageBody(jsonString(message1)).build())

    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }
    await untilCallTo { auditSqsClient.countMessagesOnQueue(auditQueueUrl).get() } matches { it == 1 }

    val receivedEvent = jsonMapper.readValue(auditSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(auditQueueUrl).build()).get().messages()[0].body(), HmppsAuditEvent::class.java)

    assertThat(receivedEvent.who).isEqualTo("me")
    assertThat(receivedEvent.what).isEqualTo("important event")
    assertThat(receivedEvent.service).isEqualTo("hmpps-template-kotlin")
    assertThat(receivedEvent.`when`).isBetween(startTime, Instant.now())
  }

  @Test
  fun `event is audited and open telemetry spans set to include the what`() = runTest {
    val event = HmppsEvent("audit-id", "OFFENDER_AUDIT-OBJECT", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(jsonString(event)).eventTypeMessageAttributes("OFFENDER_AUDIT-OBJECT").build(),
    )

    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }
    await untilCallTo { auditSqsClient.countMessagesOnQueue(auditQueueUrl).get() } matches { it == 1 }

    // And PUBLISH and RECEIVE spans are exported
    assertThat(openTelemetryExtension.spans.map { it.name }).containsAll(
      setOf(
        "PUBLISH OFFENDER_AUDIT-OBJECT",
        "RECEIVE OFFENDER_AUDIT-OBJECT",
        "PUBLISH offender.audit.object",
        "PUBLISH hmpps-audit-event",
      ),
    )
  }
}
