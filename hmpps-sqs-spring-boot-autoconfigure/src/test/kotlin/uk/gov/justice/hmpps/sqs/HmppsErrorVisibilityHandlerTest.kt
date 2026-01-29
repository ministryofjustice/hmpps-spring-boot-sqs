package uk.gov.justice.hmpps.sqs

import com.microsoft.applicationinsights.TelemetryClient
import io.awspring.cloud.sqs.listener.QueueMessageVisibility
import io.awspring.cloud.sqs.listener.SqsHeaders
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.messaging.Message
import org.springframework.messaging.support.GenericMessage
import tools.jackson.databind.json.JsonMapper

@JsonTest
class HmppsErrorVisibilityHandlerTest(@param:Autowired private val jsonMapper: JsonMapper) {

  private val queueMessageVisibility = mock<QueueMessageVisibility>()
  private val someQueue = HmppsQueue("some-queue-id", mock(), "some-queue")
  private val telemetryClient = mock<TelemetryClient>()

  fun aMessage(): Message<Any> = GenericMessage("123", mapOf("Sqs_Msa_ApproximateReceiveCount" to "1", "Sqs_VisibilityTimeout" to queueMessageVisibility))
  fun aHandler(jsonMapper: JsonMapper, properties: HmppsSqsProperties, client: TelemetryClient = telemetryClient) = HmppsErrorVisibilityHandler(jsonMapper, properties, telemetryClient)

  @Test
  fun `should set timeout to the default value of zero`() {
    val properties = HmppsSqsProperties(queues = mapOf("some-queue-id" to HmppsSqsProperties.QueueConfig("some-queue")))
    val handler = aHandler(jsonMapper, properties)

    handler.setErrorVisibilityTimeout(aMessage(), someQueue)

    verify(queueMessageVisibility).changeTo(0)
  }

  @Test
  fun `should set timeout to configured default value`() {
    val properties = HmppsSqsProperties(
      defaultErrorVisibilityTimeout = listOf(1),
      queues = mapOf("some-queue-id" to HmppsSqsProperties.QueueConfig("some-queue")),
    )
    val handler = aHandler(jsonMapper, properties)

    handler.setErrorVisibilityTimeout(aMessage(), someQueue)

    verify(queueMessageVisibility).changeTo(1)
  }

  @Test
  fun `should set timeout to the queue level timeout`() {
    val properties = HmppsSqsProperties(
      defaultErrorVisibilityTimeout = listOf(1),
      queues = mapOf(
        "some-queue-id" to HmppsSqsProperties.QueueConfig(
          "some-queue",
          errorVisibilityTimeout = listOf(2),
        ),
      ),
    )
    val handler = aHandler(jsonMapper, properties)

    handler.setErrorVisibilityTimeout(aMessage(), someQueue)

    verify(queueMessageVisibility).changeTo(2)
  }

  @Test
  fun `should set timeout to the event level timeout for a message with event type in payload MessageAttributes`() {
    val properties = HmppsSqsProperties(
      defaultErrorVisibilityTimeout = listOf(1),
      queues = mapOf(
        "some-queue-id" to HmppsSqsProperties.QueueConfig(
          "some-queue",
          errorVisibilityTimeout = listOf(2),
          eventErrorVisibilityTimeout = mapOf("some-event" to listOf(3)),
        ),
      ),
    )
    val message = GenericMessage<Any>("""{"MessageId":null,"MessageAttributes":{"eventType":{"Type": "String", "Value": "some-event"}}}""", mapOf("Sqs_Msa_ApproximateReceiveCount" to "1", "Sqs_VisibilityTimeout" to queueMessageVisibility))
    val handler = aHandler(jsonMapper, properties)

    handler.setErrorVisibilityTimeout(message, someQueue)

    verify(queueMessageVisibility).changeTo(3)
  }

  @Test
  fun `should fall back to queue timeout if event type not found in message`() {
    val properties = HmppsSqsProperties(
      defaultErrorVisibilityTimeout = listOf(1),
      queues = mapOf(
        "some-queue-id" to HmppsSqsProperties.QueueConfig(
          "some-queue",
          errorVisibilityTimeout = listOf(2),
          eventErrorVisibilityTimeout = mapOf("some-event" to listOf(3)),
        ),
      ),
    )
    val message = GenericMessage<Any>("""{"MessageId":null,"MessageAttributes":{}}""", mapOf("Sqs_Msa_ApproximateReceiveCount" to "1", "Sqs_VisibilityTimeout" to queueMessageVisibility))
    val handler = aHandler(jsonMapper, properties)

    handler.setErrorVisibilityTimeout(message, someQueue)

    verify(queueMessageVisibility).changeTo(2)
  }

  @Test
  fun `should set timeout to the event level timeout for a message with event type in header MessageAttributes`() {
    val properties = HmppsSqsProperties(
      defaultErrorVisibilityTimeout = listOf(1),
      queues = mapOf(
        "some-queue-id" to HmppsSqsProperties.QueueConfig(
          "some-queue",
          errorVisibilityTimeout = listOf(2),
          eventErrorVisibilityTimeout = mapOf("some-event" to listOf(3)),
        ),
      ),
    )
    val message = GenericMessage<Any>(
      """{"MessageId":null,"MessageAttributes":null}""",
      mapOf(
        "Sqs_Msa_ApproximateReceiveCount" to "1",
        "Sqs_VisibilityTimeout" to queueMessageVisibility,
        SqsHeaders.SQS_SOURCE_DATA_HEADER to software.amazon.awssdk.services.sqs.model.Message.builder().messageAttributes(eventTypeSqsMap("some-event")).build(),
      ),
    )
    val handler = aHandler(jsonMapper, properties)

    handler.setErrorVisibilityTimeout(message, someQueue)

    verify(queueMessageVisibility).changeTo(3)
  }

  @Test
  fun `should set timeout to value configured at receive count`() {
    val properties = HmppsSqsProperties(
      defaultErrorVisibilityTimeout = listOf(1, 2, 3),
      queues = mapOf("some-queue-id" to HmppsSqsProperties.QueueConfig("some-queue")),
    )
    val handler = aHandler(jsonMapper, properties)
    val message = GenericMessage<Any>("123", mapOf("Sqs_Msa_ApproximateReceiveCount" to "2", "Sqs_VisibilityTimeout" to queueMessageVisibility))

    handler.setErrorVisibilityTimeout(message, someQueue)

    verify(queueMessageVisibility).changeTo(2)
  }

  @Test
  fun `should set timeout to last value configured if receive count beyond configured size`() {
    val properties = HmppsSqsProperties(
      defaultErrorVisibilityTimeout = listOf(1, 2),
      queues = mapOf("some-queue-id" to HmppsSqsProperties.QueueConfig("some-queue")),
    )
    val handler = aHandler(jsonMapper, properties)
    val message = GenericMessage<Any>("123", mapOf("Sqs_Msa_ApproximateReceiveCount" to "3", "Sqs_VisibilityTimeout" to queueMessageVisibility))

    handler.setErrorVisibilityTimeout(message, someQueue)

    verify(queueMessageVisibility).changeTo(2)
  }
}
