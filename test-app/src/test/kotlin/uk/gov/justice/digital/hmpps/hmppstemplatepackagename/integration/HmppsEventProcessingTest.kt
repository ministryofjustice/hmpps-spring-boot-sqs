package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.amazonaws.services.sns.model.MessageAttributeValue
import com.amazonaws.services.sns.model.PublishRequest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.Message
import java.lang.RuntimeException

class HmppsEventProcessingTest : IntegrationTestBase() {

  @Test
  fun `event is published to outbound topic`() {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest(inboundTopicArn, gsonString(event))
        .withMessageAttributes(
          mapOf("eventType" to MessageAttributeValue().withDataType("String").withStringValue(event.type)),
        ),
    )

    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl) } matches { it == 1 }
    val (Message) = objectMapper.readValue(outboundTestSqsClient.receiveMessage(outboundTestQueueUrl).messages[0].body, Message::class.java)
    val receivedEvent = objectMapper.readValue(Message, HmppsEvent::class.java)

    assertThat(receivedEvent.id).isEqualTo("event-id")
    assertThat(receivedEvent.type).isEqualTo("offender.movement.reception")
    assertThat(receivedEvent.contents).isEqualTo("some event contents")
  }

  @Test
  fun `event is published to outbound topic but the test queue subscriber ignores it`() {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-DISCHARGE", "some event contents")
    inboundSnsClient.publish(
      PublishRequest(inboundTopicArn, gsonString(event))
        .withMessageAttributes(
          mapOf("eventType" to MessageAttributeValue().withDataType("String").withStringValue(event.type)),
        ),
    )

    await untilCallTo { mockingDetails(outboundEventsEmitterSpy).invocations!! } matches { it?.isNotEmpty() ?: false } // Don't understand why it is nullable here

    assertThat(outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl)).isEqualTo(0)
  }

  @Test
  fun `event is published to outbound topic received by queue with no dlq`() {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest(inboundTopicArn, gsonString(event))
        .withMessageAttributes(
          mapOf("eventType" to MessageAttributeValue().withDataType("String").withStringValue(event.type)),
        ),
    )

    await untilCallTo { outboundTestNoDlqSqsClient.countMessagesOnQueue(outboundTestNoDlqQueueUrl) } matches { it == 1 }
    val (Message) = objectMapper.readValue(outboundTestNoDlqSqsClient.receiveMessage(outboundTestNoDlqQueueUrl).messages[0].body, Message::class.java)
    val receivedEvent = objectMapper.readValue(Message, HmppsEvent::class.java)

    assertThat(receivedEvent.id).isEqualTo("event-id")
    assertThat(receivedEvent.type).isEqualTo("offender.movement.reception")
    assertThat(receivedEvent.contents).isEqualTo("some event contents")
  }

  @Test
  fun `event is moved to the dead letter queue when an exception is thrown`() {
    doThrow(RuntimeException("some error")).whenever(inboundMessageServiceSpy).handleMessage(any())

    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest(inboundTopicArn, gsonString(event))
        .withMessageAttributes(
          mapOf("eventType" to MessageAttributeValue().withDataType("String").withStringValue(event.type)),
        ),
    )

    await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl) } matches { it == 1 }
    assertThat(inboundSqsClient.countMessagesOnQueue(inboundQueueUrl)).isEqualTo(0)
  }
}
