package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

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
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.EventType
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.Message
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.MessageAttributes
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
    val message1 = Message(gsonString(event), "message-id1", MessageAttributes(EventType("OFFENDER_AUDIT-OBJECT", "String")))
    inboundSqsClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundQueueUrl).messageBody(gsonString(message1)).build())

    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }
    await untilCallTo { auditSqsClient.countMessagesOnQueue(auditQueueUrl).get() } matches { it == 1 }

    val receivedEvent = objectMapper.readValue(auditSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(auditQueueUrl).build()).get().messages()[0].body(), HmppsAuditEvent::class.java)

    assertThat(receivedEvent.who).isEqualTo("me")
    assertThat(receivedEvent.what).isEqualTo("important event")
    assertThat(receivedEvent.service).isEqualTo("my-special-test-app")
    assertThat(receivedEvent.`when`).isBetween(startTime, Instant.now())
  }

  @Test
  fun `event is audited and calls service with parameters`() = runTest {
    val startTime = Instant.now()
    val event = HmppsEvent("audit-id", "OFFENDER_AUDIT-PARAMETER", "some event contents")
    val message1 = Message(gsonString(event), "message-id1", MessageAttributes(EventType("OFFENDER_AUDIT-PARAMETER", "String")))
    inboundSqsClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundQueueUrl).messageBody(gsonString(message1)).build())

    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }
    await untilCallTo { auditSqsClient.countMessagesOnQueue(auditQueueUrl).get() } matches { it == 1 }

    val receivedEvent = objectMapper.readValue(auditSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(auditQueueUrl).build()).get().messages()[0].body(), HmppsAuditEvent::class.java)

    assertThat(receivedEvent.who).isEqualTo("me")
    assertThat(receivedEvent.what).isEqualTo("important event")
    assertThat(receivedEvent.service).isEqualTo("hmpps-template-kotlin")
    assertThat(receivedEvent.`when`).isBetween(startTime, Instant.now())
  }

  @Test
  fun `event is audited and open telemetry spans set to include the what`() = runTest {
    val event = HmppsEvent("audit-id", "OFFENDER_AUDIT-OBJECT", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).eventTypeMessageAttributes("OFFENDER_AUDIT-OBJECT").build(),
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
