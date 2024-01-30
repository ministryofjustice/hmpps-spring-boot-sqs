package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.EventType
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.Message
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.MessageAttributes
import uk.gov.justice.hmpps.sqs.audit.HmppsAuditEvent
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import java.time.Instant

class HmppsAuditTest : IntegrationTestBase() {

  @Test
  fun `event is audited`() = runTest {
    val startTime = Instant.now()
    val event = HmppsEvent("audit-id", "OFFENDER_MOVEMENT-IMPORTANT", "some event contents")
    val message1 = Message(gsonString(event), "message-id1", MessageAttributes(EventType("OFFENDER_MOVEMENT-IMPORTANT", "String")))
    inboundSqsClient.sendMessage(SendMessageRequest.builder().queueUrl(inboundQueueUrl).messageBody(gsonString(message1)).build())

    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }
    await untilCallTo { auditSqsClient.countMessagesOnQueue(auditQueueUrl).get() } matches { it == 1 }

    val receivedEvent = objectMapper.readValue(auditSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(auditQueueUrl).build()).get().messages()[0].body(), HmppsAuditEvent::class.java)

    assertThat(receivedEvent.who).isEqualTo("me")
    assertThat(receivedEvent.what).isEqualTo("important event")
    assertThat(receivedEvent.service).isEqualTo("test-app")
    assertThat(receivedEvent.`when`).isBetween(startTime, Instant.now())
  }
}
