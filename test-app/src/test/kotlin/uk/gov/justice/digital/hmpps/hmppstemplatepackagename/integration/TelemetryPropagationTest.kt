package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.Message
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import uk.gov.justice.hmpps.sqs.eventTypeMessageAttributes
import uk.gov.justice.hmpps.sqs.telemetry.TraceExtractingMessageInterceptor
import software.amazon.awssdk.services.sns.model.MessageAttributeValue as SnsMessageAttributeValue
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue as SqsMessageAttributeValue

class TelemetryPropagationTest : IntegrationTestBase() {
  companion object {
    @RegisterExtension
    val openTelemetryExtension: OpenTelemetryExtension = OpenTelemetryExtension.create()
  }

  @Test
  fun `telemetry information is propagated between publishers and listeners for topics `() = runTest {
    // Given a span
    val span = withSpan {
      // When I publish an OFFENDER_MOVEMENT-RECEPTION message
      val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
      inboundSnsClient.publish(
        PublishRequest.builder()
          .topicArn(inboundTopicArn)
          .message(gsonString(event))
          .messageAttributes(
            mapOf(
              "eventType" to SnsMessageAttributeValue.builder().dataType("String").stringValue(event.type).build(),
            ),
          )
          .build(),
      ).get()
    }

    // And the OFFENDER_MOVEMENT-RECEPTION message is consumed, resulting in an offender.movement.reception message being published
    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

    // Then the trace headers have been passed all the way through
    val message = objectMapper.readValue(
      outboundTestSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()).get().messages()[0].body(),
      Message::class.java,
    )
    assertThat(message.MessageAttributes["traceparent"]?.Value).contains(span.spanContext.traceId)
    assertThat(message.MessageAttributes["traceparent"]?.Value).matches("00-${span.spanContext.traceId}-[0-9a-f]{16}-01")

    // And PUBLISH and RECEIVE spans are exported
    assertThat(openTelemetryExtension.spans.map { it.name }).containsAll(
      setOf(
        "PUBLISH OFFENDER_MOVEMENT-RECEPTION",
        "RECEIVE OFFENDER_MOVEMENT-RECEPTION",
        "PUBLISH offender.movement.reception",
        "RECEIVE offender.movement.reception",
      ),
    )
  }

  @Test
  fun `telemetry information is not propagated between publishers and listeners for topics if noTracing is set`() = runTest {
    // Given a span
    val span = withSpan {
      // When I publish an OFFENDER_MOVEMENT-RECEPTION message
      val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
      inboundSnsClient.publish(
        PublishRequest.builder()
          .topicArn(inboundTopicArn)
          .message(gsonString(event))
          .eventTypeMessageAttributes(event.type, noTracing = true)
          .build(),
      )
    }

    // And the OFFENDER_MOVEMENT-RECEPTION message is consumed, resulting in an offender.movement.reception message being published
    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

    // Then the trace headers have been passed all the way through
    val message = objectMapper.readValue(
      outboundTestSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()).get().messages()[0].body(),
      Message::class.java,
    )
    assertThat(message.MessageAttributes["traceparent"]?.Value).doesNotContain(span.spanContext.traceId)

    // And PUBLISH and RECEIVE spans are exported
    assertThat(openTelemetryExtension.spans.map { it.name }).containsAll(
      setOf(
        "PUBLISH OFFENDER_MOVEMENT-RECEPTION",
        "RECEIVE OFFENDER_MOVEMENT-RECEPTION",
        "PUBLISH offender.movement.reception",
        "RECEIVE offender.movement.reception",
      ),
    )
  }

  @Test
  fun `telemetry information is propagated between publishers and listeners for queues`() = runTest {
    // Given a span
    val span = withSpan {
      // When I publish an OFFENDER_MOVEMENT-RECEPTION message
      val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
      inboundSqsOnlyClient.sendMessage(
        SendMessageRequest.builder()
          .queueUrl(inboundSqsOnlyQueueUrl)
          .messageBody(gsonString(event))
          .messageAttributes(
            mapOf(
              "eventType" to SqsMessageAttributeValue.builder().dataType("String").stringValue(event.type).build(),
            ),
          )
          .build(),
      )
    }

    // And the OFFENDER_MOVEMENT-RECEPTION message is consumed, resulting in an offender.movement.reception message being published
    await untilCallTo {
      outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get()
    } matches { it == 1 }

    // Then the trace headers have been passed all the way through
    val messages = outboundSqsOnlyTestSqsClient.receiveMessage(
      ReceiveMessageRequest.builder().queueUrl(outboundSqsOnlyTestQueueUrl).messageAttributeNames("All").build(),
    ).get().messages()
    val message = messages[0]
    assertThat(message.messageAttributes()["traceparent"]?.stringValue()).contains(span.spanContext.traceId)
    assertThat(message.messageAttributes()["traceparent"]?.stringValue()).matches("00-${span.spanContext.traceId}-[0-9a-f]{16}-01")

    // And PUBLISH and RECEIVE spans are exported
    assertThat(openTelemetryExtension.spans.map { it.name }).containsAll(
      setOf(
        "PUBLISH OFFENDER_MOVEMENT-RECEPTION",
        "RECEIVE OFFENDER_MOVEMENT-RECEPTION",
        "PUBLISH offender.movement.reception",
        "RECEIVE offender.movement.reception",
      ),
    )
  }

  @Test
  fun `telemetry information is not propagated between publishers and listeners for queues if noTracing set`() = runTest {
    // Given a span
    val span = withSpan {
      // When I publish an OFFENDER_MOVEMENT-RECEPTION message
      val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
      inboundSqsOnlyClient.sendMessage(
        SendMessageRequest.builder()
          .queueUrl(inboundSqsOnlyQueueUrl)
          .messageBody(gsonString(event))
          .eventTypeMessageAttributes(event.type, noTracing = true)
          .build(),
      )
    }

    // And the OFFENDER_MOVEMENT-RECEPTION message is consumed, resulting in an offender.movement.reception message being published
    await untilCallTo {
      outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get()
    } matches { it == 1 }

    // Then the trace headers have been passed all the way through
    val messages = outboundSqsOnlyTestSqsClient.receiveMessage(
      ReceiveMessageRequest.builder().queueUrl(outboundSqsOnlyTestQueueUrl).messageAttributeNames("All").build(),
    ).get().messages()
    val message = messages[0]
    assertThat(message.messageAttributes()["traceparent"]?.stringValue()).doesNotContain(span.spanContext.traceId)

    // And PUBLISH and RECEIVE spans are exported
    assertThat(openTelemetryExtension.spans.map { it.name }).containsAll(
      setOf(
        "PUBLISH OFFENDER_MOVEMENT-RECEPTION",
        "RECEIVE OFFENDER_MOVEMENT-RECEPTION",
        "PUBLISH offender.movement.reception",
        "RECEIVE offender.movement.reception",
      ),
    )
  }

  @Test
  fun `event is processed normally (without telemetry) when an exception is thrown during the telemetry processing`() = runTest {
    val logAppender = findLogAppender(TraceExtractingMessageInterceptor::class.java)

    withSpan {
      // having MessageAttributes in the payload will mean that the interceptor will try and parse the message
      // thinking it originated from a topic rather than a queue and thus will throw an exception
      val gsonString = """{"id":"event-id","type":"OFFENDER_MOVEMENT-RECEPTION","contents":"some contents", "MessageAttributes":"yes"}"""
      inboundSqsOnlyClient.sendMessage(
        SendMessageRequest.builder()
          .queueUrl(inboundSqsOnlyQueueUrl)
          .messageBody(gsonString)
          .messageAttributes(
            mapOf(
              "eventType" to SqsMessageAttributeValue.builder().dataType("String").stringValue("OFFENDER_MOVEMENT-RECEPTION").build(),
            ),
          )
          .build(),
      )
    }

    // message is processed as normal though, resulting in an offender.movement.reception message being published
    await untilCallTo { outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get() } matches { it == 1 }

    // we can then check to ensure that the interceptor did indeed throw the exception but carried on regardless
    assertThat(logAppender.list).anyMatch { it.message.contains("Not attempting to extract trace context") && it.level == Level.ERROR }
  }

  @Test
  fun `span is recorded as failed if exception occurs during processing`() = runTest {
    doThrow(RuntimeException("failed to process")).whenever(outboundEventsEmitterSpy).sendEvent(any())

    // Given a span
    withSpan {
      // When I publish an OFFENDER_MOVEMENT-RECEPTION message
      val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
      inboundSnsClient.publish(
        PublishRequest.builder()
          .topicArn(inboundTopicArn)
          .message(gsonString(event))
          .messageAttributes(
            mapOf(
              "eventType" to SnsMessageAttributeValue.builder().dataType("String").stringValue(event.type).build(),
            ),
          )
          .build(),
      ).get()
    }

    // And the OFFENDER_MOVEMENT-RECEPTION message is consumed, resulting in an offender.movement.reception message being published
    await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }

    // And PUBLISH and RECEIVE spans are exported
    assertThat(openTelemetryExtension.spans.map { it.name }).containsExactlyInAnyOrder(
      "PUBLISH OFFENDER_MOVEMENT-RECEPTION",
      "test-span",
      "RECEIVE OFFENDER_MOVEMENT-RECEPTION",
    )
    val spanner = openTelemetryExtension.spans.find { it.name == "RECEIVE OFFENDER_MOVEMENT-RECEPTION" }
    // span is recorded as a failure
    assertThat(spanner?.status?.statusCode).isEqualTo(StatusCode.ERROR)
    // and exception stored as well
    assertThat(spanner?.events?.map { it.name }).containsExactly("exception")
  }

  @Test
  fun `span is recorded as succeeded with no exception present if processing terminates normally`() = runTest {
    // Given a span
    val span = withSpan {
      // When I publish an OFFENDER_MOVEMENT-RECEPTION message
      val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
      inboundSqsOnlyClient.sendMessage(
        SendMessageRequest.builder()
          .queueUrl(inboundSqsOnlyQueueUrl)
          .messageBody(gsonString(event))
          .messageAttributes(
            mapOf(
              "eventType" to software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder().dataType("String").stringValue(event.type).build(),
            ),
          )
          .build(),
      )
    }

    // And the OFFENDER_MOVEMENT-RECEPTION message is consumed, resulting in an offender.movement.reception message being published
    await untilCallTo {
      outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get()
    } matches { it == 1 }

    // And PUBLISH and RECEIVE spans are exported
    assertThat(TelemetryPropagationTest.openTelemetryExtension.spans.map { it.name }).containsAll(
      setOf(
        "PUBLISH OFFENDER_MOVEMENT-RECEPTION",
        "RECEIVE OFFENDER_MOVEMENT-RECEPTION",
        "PUBLISH offender.movement.reception",
        "RECEIVE offender.movement.reception",
      ),
    )
    val receive = TelemetryPropagationTest.openTelemetryExtension.spans.filter { it.name.startsWith("RECEIVE") }
    // span status is always unset
    assertThat(receive.map { it.status.statusCode }).containsOnly(StatusCode.UNSET)
    // and exceptions are not stored
    assertThat(receive.flatMap { it.events }).isEmpty()
  }

  private fun withSpan(block: () -> Unit): Span {
    val span = openTelemetryExtension.openTelemetry.getTracer("hmpps-sqs").spanBuilder("test-span").startSpan()
    Context.current().with(span).makeCurrent().use { block() }
    return span.also { it.end() }
  }

  @Test
  fun `spans contain enhanced OpenTelemetry messaging attributes`() = runTest {
    // Given a span
    withSpan {
      // When I publish an OFFENDER_MOVEMENT-RECEPTION message
      val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
      inboundSqsOnlyClient.sendMessage(
        SendMessageRequest.builder()
          .queueUrl(inboundSqsOnlyQueueUrl)
          .messageBody(gsonString(event))
          .messageAttributes(
            mapOf(
              "eventType" to SqsMessageAttributeValue.builder().dataType("String").stringValue(event.type).build(),
            ),
          )
          .build(),
      )
    }

    // And the OFFENDER_MOVEMENT-RECEPTION message is consumed
    await untilCallTo {
      outboundSqsOnlyTestSqsClient.countMessagesOnQueue(outboundSqsOnlyTestQueueUrl).get()
    } matches { it == 1 }

    // Then verify enhanced OpenTelemetry attributes are present
    val spans = openTelemetryExtension.spans

    // Find PUBLISH and RECEIVE spans
    val publishSpan = spans.find { it.name == "PUBLISH OFFENDER_MOVEMENT-RECEPTION" }
    val receiveSpan = spans.find { it.name == "RECEIVE OFFENDER_MOVEMENT-RECEPTION" }

    assertThat(publishSpan).isNotNull
    assertThat(receiveSpan).isNotNull

    // Verify PUBLISH span attributes
    publishSpan?.let { span ->
      val attributes = span.attributes
      assertThat(attributes.get(AttributeKey.stringKey("messaging.system"))).isEqualTo("aws_sqs")
      assertThat(attributes.get(AttributeKey.stringKey("messaging.operation.type"))).isEqualTo("send")
      assertThat(attributes.get(AttributeKey.stringKey("messaging.operation.name"))).isEqualTo("sendMessage")
      assertThat(attributes.get(AttributeKey.stringKey("server.address"))).isEqualTo("sqs.amazonaws.com")
      assertThat(attributes.get(AttributeKey.longKey("server.port"))).isEqualTo(443L)
      assertThat(attributes.get(AttributeKey.stringKey("messaging.message.conversation_id"))).isEqualTo("OFFENDER_MOVEMENT-RECEPTION")
      // Destination name should be extracted from queue URL
      assertThat(attributes.get(AttributeKey.stringKey("messaging.destination.name"))).isNotNull()
    }

    // Verify RECEIVE span attributes
    receiveSpan?.let { span ->
      val attributes = span.attributes
      assertThat(attributes.get(AttributeKey.stringKey("messaging.system"))).isEqualTo("aws_sqs")
      assertThat(attributes.get(AttributeKey.stringKey("messaging.operation.type"))).isEqualTo("receive")
      assertThat(attributes.get(AttributeKey.stringKey("messaging.operation.name"))).isEqualTo("receiveMessage")
      assertThat(attributes.get(AttributeKey.stringKey("server.address"))).isEqualTo("sqs.amazonaws.com")
      assertThat(attributes.get(AttributeKey.longKey("server.port"))).isEqualTo(443L)
      assertThat(attributes.get(AttributeKey.stringKey("messaging.message.conversation_id"))).isEqualTo("OFFENDER_MOVEMENT-RECEPTION")
      // Message ID should be present from SQS
      assertThat(attributes.get(AttributeKey.stringKey("messaging.message.id"))).isNotNull()
    }
  }

  fun <T> findLogAppender(javaClass: Class<in T>): ListAppender<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(javaClass) as Logger
    val listAppender = ListAppender<ILoggingEvent>()
    listAppender.start()
    logger.addAppender(listAppender)
    return listAppender
  }
}
