package uk.gov.justice.hmpps.sqs.telemetry

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.awspring.cloud.sqs.MessageHeaderUtils
import io.awspring.cloud.sqs.listener.interceptor.MessageInterceptor
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapGetter
import org.springframework.messaging.Message

/**
 * Intercepts messages before they are passed to the `@SqsListener`.
 *
 * On receiving a message, this interceptor will:
 *  1. attempt to extract the trace context from the message attributes,
 *  2. start a new OpenTelemetry span with the name "RECEIVE $eventType",
 *  3. end the span once the message has finished processing.
 */
class TraceExtractingMessageInterceptor(private val objectMapper: ObjectMapper) : MessageInterceptor<Any> {
  override fun intercept(message: Message<Any>): Message<Any> {
    val payload = message.payload
    if (payload !is String) return message

    val attributes = objectMapper.readValue(
      objectMapper.readTree(payload).at("/MessageAttributes").traverse(),
      object : TypeReference<MutableMap<String, MessageAttribute>>() {},
    )
    val spanName = attributes["eventType"]?.let { "RECEIVE ${it.Value}" } ?: "RECEIVE"
    val span = attributes.extractTelemetryContext().startSpan(spanName)

    return message
      .withHeader("span", span)
      .withHeader("scope", span.makeCurrent())
  }

  override fun afterProcessing(message: Message<Any>, t: Throwable?) {
    (message.headers["span"] as Span?)?.end()
    (message.headers["scope"] as Scope?)?.close()
  }

  override fun intercept(messages: Collection<Message<Any>>) = messages.map { intercept(it) }

  override fun afterProcessing(messages: Collection<Message<Any>>, t: Throwable?) =
    messages.forEach { afterProcessing(it, t) }

  private fun <T> Message<T>.withHeader(headerName: String, headerValue: Any) =
    MessageHeaderUtils.addHeaderIfAbsent(this, headerName, headerValue)

  private fun MutableMap<String, MessageAttribute>.extractTelemetryContext(): Context {
    val getter = object : TextMapGetter<MutableMap<String, MessageAttribute>> {
      override fun keys(carrier: MutableMap<String, MessageAttribute>) = carrier.keys
      override fun get(carrier: MutableMap<String, MessageAttribute>?, key: String) =
        carrier?.get(key)?.Value.toString()
    }
    return GlobalOpenTelemetry.getPropagators().textMapPropagator.extract(Context.current(), this, getter)
  }

  private fun Context.startSpan(spanName: String): Span = GlobalOpenTelemetry
    .getTracer("hmpps-sqs")
    .spanBuilder(spanName)
    .setParent(this)
    .setSpanKind(SpanKind.CONSUMER)
    .startSpan()

  private class MessageAttribute(val Type: String, val Value: Any?)
}
