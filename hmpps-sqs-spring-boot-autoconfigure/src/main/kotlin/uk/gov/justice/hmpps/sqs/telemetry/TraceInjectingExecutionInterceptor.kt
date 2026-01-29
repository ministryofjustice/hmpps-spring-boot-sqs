package uk.gov.justice.hmpps.sqs.telemetry

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import software.amazon.awssdk.core.interceptor.Context
import software.amazon.awssdk.core.interceptor.ExecutionAttributes
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import io.opentelemetry.context.Context as SpanContext
import software.amazon.awssdk.services.sns.model.MessageAttributeValue as SnsMessageAttributeValue
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue as SqsMessageAttributeValue

/**
 * Intercepts `PublishRequest` and `SendMessageRequest` requests, and
 *  1. creates an OpenTelemetry span with the name "PUBLISH $eventType",
 *  2. injects the trace context into the message attributes before sending.
 */
class TraceInjectingExecutionInterceptor : ExecutionInterceptor {
  override fun modifyRequest(context: Context.ModifyRequest?, executionAttributes: ExecutionAttributes?) = when (val request = context?.request()) {
    is PublishRequest -> withTopicSpan(
      eventType = request.messageAttributes()["eventType"]?.stringValue(),
      topicArn = request.topicArn(),
    ) {
      request.toBuilder().messageAttributes(request.messageAttributes().withSnsTelemetryContext()).build()
    }

    is PublishBatchRequest -> withTopicSpan(topicArn = request.topicArn()) {
      request.toBuilder().publishBatchRequestEntries(
        request.publishBatchRequestEntries().map { entry ->
          entry.toBuilder().messageAttributes(entry.messageAttributes().withSnsTelemetryContext()).build()
        },
      ).build()
    }

    is SendMessageRequest -> withQueueSpan(
      eventType = request.messageAttributes()["eventType"]?.stringValue(),
      queueUrl = request.queueUrl(),
    ) {
      request.toBuilder().messageAttributes(request.messageAttributes().withSqsTelemetryContext()).build()
    }

    is SendMessageBatchRequest -> withQueueSpan(queueUrl = request.queueUrl()) {
      request.toBuilder().entries(
        request.entries().map { entry ->
          entry.toBuilder().messageAttributes(entry.messageAttributes().withSqsTelemetryContext()).build()
        },
      ).build()
    }

    else -> request
  }

  private fun <T> withTopicSpan(
    eventType: String? = null,
    topicArn: String? = null,
    block: () -> T,
  ): T = withSpan(
    eventType = eventType,
    system = "aws.sns",
    queueOrTopicName = topicArn?.substringAfterLast(":"),
    block = block,
  )

  private fun <T> withQueueSpan(
    eventType: String? = null,
    queueUrl: String? = null,
    block: () -> T,
  ): T = withSpan(
    eventType = eventType,
    // odd to use a _ here instead of a . like in aws.sns, but that is how it is specified in the standard.
    system = "aws_sqs",
    queueOrTopicName = queueUrl?.substringAfterLast("/"),
    block = block,
  )

  private fun <T> withSpan(
    eventType: String? = null,
    system: String,
    queueOrTopicName: String? = null,
    block: () -> T,
  ): T = GlobalOpenTelemetry
    .getTracer("hmpps-sqs")
    .spanBuilder(eventType?.let { "PUBLISH $it" } ?: "PUBLISH")
    // Set standard messaging attributes - see https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/
    .setAttribute("messaging.system", system)
    .setAttribute("messaging.operation.type", "send")
    .setAttribute("messaging.destination.name", queueOrTopicName ?: "unknown")
    .setSpanKind(SpanKind.PRODUCER)
    .startSpan()
    .let {
      try {
        it.makeCurrent().use { block() }
      } finally {
        it.end()
      }
    }

  private fun MutableMap<String, SnsMessageAttributeValue>.withSnsTelemetryContext() = toMutableMap().also {
    if (it["noTracing"]?.stringValue()?.equals("true") == true) return it

    val context = SpanContext.current().with(Span.current())
    GlobalOpenTelemetry.getPropagators().textMapPropagator.inject(context, it) { carrier, key, value ->
      carrier!![key] = SnsMessageAttributeValue.builder().dataType("String").stringValue(value).build()
    }
  }

  private fun MutableMap<String, SqsMessageAttributeValue>.withSqsTelemetryContext() = toMutableMap().also {
    if (it["noTracing"]?.stringValue()?.equals("true") == true) return it

    val context = SpanContext.current().with(Span.current())
    GlobalOpenTelemetry.getPropagators().textMapPropagator.inject(context, it) { carrier, key, value ->
      carrier!![key] = SqsMessageAttributeValue.builder().dataType("String").stringValue(value).build()
    }
  }
}
