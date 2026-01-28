package uk.gov.justice.hmpps.sqs.telemetry

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.awspring.cloud.sqs.MessageHeaderUtils
import io.awspring.cloud.sqs.listener.SqsHeaders
import io.awspring.cloud.sqs.listener.interceptor.MessageInterceptor
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.TextMapGetter
import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import java.util.concurrent.CompletionException
import software.amazon.awssdk.services.sqs.model.Message as SqsMessage

/**
 * Intercepts messages before they are passed to the `@SqsListener`.
 *
 * On receiving a message, this interceptor will:
 *  1. attempt to extract the trace context from the message attributes,
 *  2. start a new OpenTelemetry span with the name "RECEIVE $eventType",
 *  3. end the span once the message has finished processing.
 *
 * Note that we have to wrap the whole processing in a try catch block since exceptions thrown from a MessageInterceptor
 * cause the message to be lost and not go on the dead letter queue.
 */
class TraceExtractingMessageInterceptor(private val objectMapper: ObjectMapper) : MessageInterceptor<Any> {
  override fun intercept(message: Message<Any>): Message<Any> = try {
    // seems to be only true for messages that originated on a topic
    val payload = message.payload as? String
    val span = (if (payload?.contains("MessageAttributes") == true) startSpanFromAttributesInPayload(payload) else null)
      // otherwise we have to grab the attributes from the message
      // unfortunately these appear to then be not populated for a topic, so have to do both
      ?: startSpanFromAttributesInHeader(message)
    span?.let { message.withHeader("span", span).withHeader("scope", span.makeCurrent()) } ?: message
  } catch (e: Exception) {
    log.error("Not attempting to extract trace context from message: {} with headers: {} due to exception", message.payload, message.headers, e)
    message
  }

  private fun startSpanFromAttributesInHeader(message: Message<Any>): Span? = extractAttributes(message)?.let { attributes ->
    val spanName = attributes["eventType"]?.let { "RECEIVE ${it.stringValue()}" } ?: "RECEIVE"
    attributes.extractTelemetryContextFromValues().startSpan(spanName)
  }

  private fun startSpanFromAttributesInPayload(payload: String?): Span? {
    val attributes = objectMapper.readValue(
      objectMapper.readTree(payload).at("/MessageAttributes").traverse(),
      object : TypeReference<MutableMap<String, MessageAttribute>>() {},
    )
    val spanName = attributes?.get("eventType")?.let { "RECEIVE ${it.Value}" } ?: "RECEIVE"
    return attributes?.extractTelemetryContext()?.startSpan(spanName)
  }

  private fun extractAttributes(message: Message<Any>): MutableMap<String, MessageAttributeValue>? {
    val headers = message.headers[SqsHeaders.SQS_SOURCE_DATA_HEADER] as? SqsMessage
    return headers?.messageAttributes() ?: run {
      log.info("Unable to find header {} message attributes from message: {} with headers: {}", SqsHeaders.SQS_SOURCE_DATA_HEADER, message.payload, message.headers)
      null
    }
  }

  override fun afterProcessing(message: Message<Any>, t: Throwable?) {
    (message.headers["span"] as Span?)?.run {
      // Set standard OpenTelemetry messaging attributes
      this.setAttribute("messaging.system", "aws_sqs")
      this.setAttribute("messaging.operation.type", "receive")
      this.setAttribute("messaging.operation.name", "receiveMessage")

      // Add enhanced messaging attributes
      val sqsMessage = message.headers[SqsHeaders.SQS_SOURCE_DATA_HEADER] as? SqsMessage
      sqsMessage?.let { sqs ->
        // Message ID for correlation
        this.setAttribute("messaging.message.id", sqs.messageId())

        // Extract queue name from source queue URL if available
        message.headers[SqsHeaders.SQS_QUEUE_URL_HEADER]?.let { queueUrl ->
          val queueName = (queueUrl as String).substringAfterLast("/")
          this.setAttribute("messaging.destination.name", queueName)
        }

        // AWS region endpoint
        this.setAttribute("server.address", "sqs.amazonaws.com")
        this.setAttribute("server.port", 443L)

        // Conversation ID from eventType if available
        sqs.messageAttributes()["eventType"]?.let { eventType ->
          this.setAttribute("messaging.message.conversation_id", eventType.stringValue())
        }
      }

      if (t != null) {
        // by grabbing the cause and recording the exception here we can then use app insights to filter out
        // java.util.concurrent.CompletionException exception messages separately.  This stops the exception being
        // logged twice.  It is more important to record this one as it will contain the OperationId and OperationName.
        this.recordException(if (t is CompletionException) t.cause else t)
        this.setStatus(StatusCode.ERROR)
      }
      this.end()
    }
    (message.headers["scope"] as Scope?)?.close()
  }

  override fun intercept(messages: Collection<Message<Any>>) = messages.map { intercept(it) }

  override fun afterProcessing(messages: Collection<Message<Any>>, t: Throwable?) = messages.forEach { afterProcessing(it, t) }

  private fun <T : Any> Message<T>.withHeader(headerName: String, headerValue: Any) = MessageHeaderUtils.addHeaderIfAbsent(this, headerName, headerValue)

  private fun MutableMap<String, MessageAttributeValue>.extractTelemetryContextFromValues(): Context {
    val getter = object : TextMapGetter<MutableMap<String, MessageAttributeValue>> {
      override fun keys(carrier: MutableMap<String, MessageAttributeValue>) = carrier.keys
      override fun get(carrier: MutableMap<String, MessageAttributeValue>?, key: String) = carrier?.get(key)?.stringValue()
    }
    return GlobalOpenTelemetry.getPropagators().textMapPropagator.extract(Context.current(), this, getter)
  }

  private fun MutableMap<String, MessageAttribute>.extractTelemetryContext(): Context {
    val getter = object : TextMapGetter<MutableMap<String, MessageAttribute>> {
      override fun keys(carrier: MutableMap<String, MessageAttribute>) = carrier.keys
      override fun get(carrier: MutableMap<String, MessageAttribute>?, key: String) = carrier?.get(key)?.Value.toString()
    }
    return GlobalOpenTelemetry.getPropagators().textMapPropagator.extract(Context.current(), this, getter)
  }

  private fun Context.startSpan(spanName: String): Span = GlobalOpenTelemetry
    .getTracer("hmpps-sqs")
    .spanBuilder(spanName)
    .setParent(this)
    .setSpanKind(SpanKind.CONSUMER)
    .startSpan()

  private class MessageAttribute(
    @param:JsonProperty("Type") val Type: String,
    @param:JsonProperty("Value") val Value: Any?,
  )
  companion object {
    private val log = LoggerFactory.getLogger(TraceExtractingMessageInterceptor::class.java)
  }
}
