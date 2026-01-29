package uk.gov.justice.hmpps.sqs.telemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.interceptor.Context
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sns.model.MessageAttributeValue as SnsMessageAttributeValue
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue as SqsMessageAttributeValue

class TraceInjectingExecutionInterceptorTest {
  private val interceptor = TraceInjectingExecutionInterceptor()
  private val modifyRequest: Context.ModifyRequest = mock()

  companion object {
    @RegisterExtension
    val openTelemetryExtension: OpenTelemetryExtension = OpenTelemetryExtension.create()
  }

  @Test
  fun `should do nothing if not a publish or send request`() {
    whenever(modifyRequest.request()).thenReturn(null)
    interceptor.modifyRequest(modifyRequest, null)

    assertThat(openTelemetryExtension.spans).isEmpty()
  }

  @Test
  fun `should start a new span if PublishRequest`() {
    whenever(modifyRequest.request()).thenReturn(
      PublishRequest.builder()
        .topicArn("arn:aws:sns:eu-west-2:123456789012:topic-name")
        .messageAttributes(
          mapOf(
            "eventType" to SnsMessageAttributeValue.builder().stringValue("my-event").dataType("String").build(),
          ),
        ).build(),
    )
    interceptor.modifyRequest(modifyRequest, null)

    assertThat(openTelemetryExtension.spans).hasSize(1)
    assertThat(openTelemetryExtension.spans.map { it.name }).contains("PUBLISH my-event")
    val attributes = openTelemetryExtension.spans.find { it.name == "PUBLISH my-event" }!!.attributes

    assertThat(attributes.get(AttributeKey.stringKey("messaging.system"))).isEqualTo("aws.sns")
    assertThat(attributes.get(AttributeKey.stringKey("messaging.operation.type"))).isEqualTo("send")
    assertThat(attributes.get(AttributeKey.stringKey("messaging.destination.name"))).isEqualTo("topic-name")
  }

  @Test
  fun `should start a new span if PublishRequest without an event type`() {
    whenever(modifyRequest.request()).thenReturn(
      PublishRequest.builder()
        .topicArn("arn:aws:sns:eu-west-2:123456789012:topic-name")
        .build(),
    )
    interceptor.modifyRequest(modifyRequest, null)

    assertThat(openTelemetryExtension.spans).hasSize(1)
    assertThat(openTelemetryExtension.spans.map { it.name }).contains("PUBLISH")
  }

  @Test
  fun `should start a new span if PublishRequest without a topic name`() {
    whenever(modifyRequest.request()).thenReturn(
      PublishRequest.builder()
        .messageAttributes(
          mapOf(
            "eventType" to SnsMessageAttributeValue.builder().stringValue("my-event").dataType("String").build(),
          ),
        ).build(),
    )
    interceptor.modifyRequest(modifyRequest, null)

    assertThat(openTelemetryExtension.spans).hasSize(1)
    assertThat(openTelemetryExtension.spans.map { it.name }).contains("PUBLISH my-event")
    val attributes = openTelemetryExtension.spans.find { it.name == "PUBLISH my-event" }!!.attributes

    assertThat(attributes.get(AttributeKey.stringKey("messaging.destination.name"))).isEqualTo("unknown")
  }

  @Test
  fun `should start a new span if PublishBatchRequest`() {
    whenever(modifyRequest.request()).thenReturn(
      PublishBatchRequest.builder()
        .topicArn("arn:aws:sns:eu-west-2:123456789012:topic-name")
        .publishBatchRequestEntries(
          PublishBatchRequestEntry.builder()
            .messageAttributes(
              mapOf(
                "eventType" to SnsMessageAttributeValue.builder().stringValue("my-event").dataType("String").build(),
              ),
            ).build(),
        ).build(),
    )
    interceptor.modifyRequest(modifyRequest, null)

    assertThat(openTelemetryExtension.spans).hasSize(1)
    assertThat(openTelemetryExtension.spans.map { it.name }).contains("PUBLISH")
    val attributes = openTelemetryExtension.spans.find { it.name == "PUBLISH" }!!.attributes

    assertThat(attributes.get(AttributeKey.stringKey("messaging.system"))).isEqualTo("aws.sns")
    assertThat(attributes.get(AttributeKey.stringKey("messaging.operation.type"))).isEqualTo("send")
    assertThat(attributes.get(AttributeKey.stringKey("messaging.destination.name"))).isEqualTo("topic-name")
  }

  @Test
  fun `should start a new span if SendMessageRequest`() {
    whenever(modifyRequest.request()).thenReturn(
      SendMessageRequest.builder()
        .queueUrl("https://sqs.eu-west-2.amazonaws.com/123456789012/queue-name")
        .messageAttributes(
          mapOf(
            "eventType" to SqsMessageAttributeValue.builder().stringValue("my-event").dataType("String").build(),
          ),
        ).build(),
    )
    interceptor.modifyRequest(modifyRequest, null)

    assertThat(openTelemetryExtension.spans).hasSize(1)
    assertThat(openTelemetryExtension.spans.map { it.name }).contains("PUBLISH my-event")
    val attributes = openTelemetryExtension.spans.find { it.name == "PUBLISH my-event" }!!.attributes

    assertThat(attributes.get(AttributeKey.stringKey("messaging.system"))).isEqualTo("aws_sqs")
    assertThat(attributes.get(AttributeKey.stringKey("messaging.operation.type"))).isEqualTo("send")
    assertThat(attributes.get(AttributeKey.stringKey("messaging.destination.name"))).isEqualTo("queue-name")
  }

  @Test
  fun `should start a new span if SendMessageBatchRequest`() {
    whenever(modifyRequest.request()).thenReturn(
      SendMessageBatchRequest.builder()
        .queueUrl("https://sqs.eu-west-2.amazonaws.com/123456789012/queue-name")
        .entries(
          SendMessageBatchRequestEntry.builder()
            .messageAttributes(
              mapOf(
                "eventType" to SqsMessageAttributeValue.builder().stringValue("my-event").dataType("String").build(),
              ),
            ).build(),
        ).build(),
    )
    interceptor.modifyRequest(modifyRequest, null)

    assertThat(openTelemetryExtension.spans).hasSize(1)
    assertThat(openTelemetryExtension.spans.map { it.name }).contains("PUBLISH")
    val attributes = openTelemetryExtension.spans.find { it.name == "PUBLISH" }!!.attributes

    assertThat(attributes.get(AttributeKey.stringKey("messaging.system"))).isEqualTo("aws_sqs")
    assertThat(attributes.get(AttributeKey.stringKey("messaging.operation.type"))).isEqualTo("send")
    assertThat(attributes.get(AttributeKey.stringKey("messaging.destination.name"))).isEqualTo("queue-name")
  }
}
