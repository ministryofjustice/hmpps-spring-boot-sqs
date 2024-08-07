package uk.gov.justice.hmpps.sqs.telemetry

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.awspring.cloud.sqs.MessageHeaderUtils
import io.awspring.cloud.sqs.listener.SqsHeaders
import io.awspring.cloud.sqs.listener.interceptor.MessageInterceptor
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapGetter
import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.Message as SqsMessage

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
    val payload = message.payload as? String
    // seems to be only true for messages that originated on a topic
    val span = if (payload?.contains("MessageAttributes") == true) {
      val attributes = objectMapper.readValue(
        objectMapper.readTree(payload).at("/MessageAttributes").traverse(),
        object : TypeReference<MutableMap<String, MessageAttribute>>() {},
      )
      val spanName = attributes["eventType"]?.let { "RECEIVE ${it.Value}" } ?: "RECEIVE"
      attributes.extractTelemetryContext().startSpan(spanName)
    } else {
      // otherwise we have to grab the attributes from the message
      // unfortunately these appear to then be not populated for a topic, so have to do both
      extractAttributes(message)?.let { attributes ->
        val spanName = attributes["eventType"]?.let { "RECEIVE ${it.stringValue()}" } ?: "RECEIVE"
        attributes.extractTelemetryContextFromValues().startSpan(spanName)
      }
    }
    return if (span == null) {
      message
    } else {
      message.withHeader("span", span).withHeader("scope", span.makeCurrent())
    }
  }

  private fun extractAttributes(message: Message<Any>): MutableMap<String, MessageAttributeValue>? {
    val headers = message.headers[SqsHeaders.SQS_SOURCE_DATA_HEADER] as? SqsMessage
    return headers?.messageAttributes() ?: run {
      log.info("Unable to find header {} message attributes from message: {} with headers: {}", SqsHeaders.SQS_SOURCE_DATA_HEADER, message.payload, message.headers)
      null
    }
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

  private fun MutableMap<String, MessageAttributeValue>.extractTelemetryContextFromValues(): Context {
    val getter = object : TextMapGetter<MutableMap<String, MessageAttributeValue>> {
      override fun keys(carrier: MutableMap<String, MessageAttributeValue>) = carrier.keys
      override fun get(carrier: MutableMap<String, MessageAttributeValue>?, key: String) =
        carrier?.get(key)?.stringValue()
    }
    return GlobalOpenTelemetry.getPropagators().textMapPropagator.extract(Context.current(), this, getter)
  }

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

  companion object {
    private val log = LoggerFactory.getLogger(TraceExtractingMessageInterceptor::class.java)
  }
}
