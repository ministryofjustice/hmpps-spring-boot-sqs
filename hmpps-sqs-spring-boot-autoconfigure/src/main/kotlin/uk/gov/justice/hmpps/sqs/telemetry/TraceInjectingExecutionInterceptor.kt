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
  override fun modifyRequest(context: Context.ModifyRequest?, executionAttributes: ExecutionAttributes?) =
    when (val request = context?.request()) {
      is PublishRequest -> withSpan(request.messageAttributes()["eventType"]?.stringValue()) {
        request.toBuilder().messageAttributes(request.messageAttributes().withSnsTelemetryContext()).build()
      }

      is PublishBatchRequest -> withSpan {
        request.toBuilder().publishBatchRequestEntries(
          request.publishBatchRequestEntries().map { entry ->
            entry.toBuilder().messageAttributes(entry.messageAttributes().withSnsTelemetryContext()).build()
          },
        ).build()
      }

      is SendMessageRequest -> withSpan(request.messageAttributes()["eventType"]?.stringValue()) {
        request.toBuilder().messageAttributes(request.messageAttributes().withSqsTelemetryContext()).build()
      }

      is SendMessageBatchRequest -> withSpan {
        request.toBuilder().entries(
          request.entries().map { entry ->
            entry.toBuilder().messageAttributes(entry.messageAttributes().withSqsTelemetryContext()).build()
          },
        ).build()
      }

      else -> request
    }

  private fun <T> withSpan(eventType: String? = null, block: () -> T): T = GlobalOpenTelemetry
    .getTracer("hmpps-sqs")
    .spanBuilder(eventType?.let { "PUBLISH $it" } ?: "PUBLISH")
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
    val context = SpanContext.current().with(Span.current())
    GlobalOpenTelemetry.getPropagators().textMapPropagator.inject(context, it) { carrier, key, value ->
      carrier!![key] = SnsMessageAttributeValue.builder().dataType("String").stringValue(value).build()
    }
  }

  private fun MutableMap<String, SqsMessageAttributeValue>.withSqsTelemetryContext() = toMutableMap().also {
    val context = SpanContext.current().with(Span.current())
    GlobalOpenTelemetry.getPropagators().textMapPropagator.inject(context, it) { carrier, key, value ->
      carrier!![key] = SqsMessageAttributeValue.builder().dataType("String").stringValue(value).build()
    }
  }
}
