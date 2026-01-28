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
    is PublishRequest -> withSpan(
      eventType = request.messageAttributes()["eventType"]?.stringValue(),
      system = "aws.sns",
      operationName = "publish",
      topicArn = request.topicArn(),
    ) {
      request.toBuilder().messageAttributes(request.messageAttributes().withSnsTelemetryContext()).build()
    }

    is PublishBatchRequest -> withSpan(
      system = "aws.sns",
      operationName = "publishBatch",
      topicArn = request.topicArn(),
      batchSize = request.publishBatchRequestEntries().size,
    ) {
      request.toBuilder().publishBatchRequestEntries(
        request.publishBatchRequestEntries().map { entry ->
          entry.toBuilder().messageAttributes(entry.messageAttributes().withSnsTelemetryContext()).build()
        },
      ).build()
    }

    is SendMessageRequest -> withSpan(
      eventType = request.messageAttributes()["eventType"]?.stringValue(),
      system = "aws_sqs",
      operationName = "sendMessage",
      queueUrl = request.queueUrl(),
    ) {
      request.toBuilder().messageAttributes(request.messageAttributes().withSqsTelemetryContext()).build()
    }

    is SendMessageBatchRequest -> withSpan(
      system = "aws_sqs",
      operationName = "sendMessageBatch",
      queueUrl = request.queueUrl(),
      batchSize = request.entries().size,
    ) {
      request.toBuilder().entries(
        request.entries().map { entry ->
          entry.toBuilder().messageAttributes(entry.messageAttributes().withSqsTelemetryContext()).build()
        },
      ).build()
    }

    else -> request
  }

  private fun <T> withSpan(
    eventType: String? = null,
    system: String,
    operationName: String,
    queueUrl: String? = null,
    topicArn: String? = null,
    batchSize: Int? = null,
    block: () -> T,
  ): T = GlobalOpenTelemetry
    .getTracer("hmpps-sqs")
    .spanBuilder(eventType?.let { "PUBLISH $it" } ?: "PUBLISH")
    .setSpanKind(SpanKind.PRODUCER)
    .startSpan()
    .let { span ->
      try {
        span.makeCurrent().use {
          // Set standard OpenTelemetry messaging attributes
          span.setAttribute("messaging.system", system)
          span.setAttribute("messaging.operation.type", "send")
          span.setAttribute("messaging.operation.name", operationName)

          // Enhanced attributes
          span.setAttribute("server.address", if (system == "aws_sqs") "sqs.amazonaws.com" else "sns.amazonaws.com")
          span.setAttribute("server.port", 443L)

          // Destination name from URL/ARN
          queueUrl?.let { url ->
            val queueName = url.substringAfterLast("/")
            span.setAttribute("messaging.destination.name", queueName)
          }

          topicArn?.let { arn ->
            val topicName = arn.substringAfterLast(":")
            span.setAttribute("messaging.destination.name", topicName)
          }

          // Batch size
          batchSize?.let { size ->
            span.setAttribute("messaging.batch.message_count", size.toLong())
          }

          // Conversation ID from eventType
          eventType?.let {
            span.setAttribute("messaging.message.conversation_id", it)
          }

          block()
        }
      } finally {
        span.end()
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
