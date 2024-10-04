package uk.gov.justice.hmpps.sqs.telemetry

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import io.awspring.cloud.sqs.listener.SqsHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.messaging.support.GenericMessage
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.justice.hmpps.sqs.eventTypeSqsMap
import uk.gov.justice.hmpps.sqs.findLogAppender
import uk.gov.justice.hmpps.sqs.formattedMessages

@JsonTest
class TraceExtractingMessageInterceptorTest(@Autowired private val objectMapper: ObjectMapper) {
  private lateinit var logger: ListAppender<ILoggingEvent>

  @BeforeEach
  fun setupLogAppender() {
    logger = findLogAppender(TraceExtractingMessageInterceptor::class.java)
  }

  @Test
  fun `should do nothing if no MessageAttributes header found`() {
    val message = GenericMessage<Any>("123", mapOf("eventType" to "myevent"))

    val responseMessage = TraceExtractingMessageInterceptor(objectMapper).intercept(message)

    assertThat(responseMessage.headers).isEqualTo(message.headers)
    // no new span will be created, so don't expect these keys
    assertThat(responseMessage.headers).doesNotContainKeys("span", "scope")

    // and log message informing us that it couldn't find the header message attributes
    assertThat(logger.formattedMessages()).anyMatch { it.contains("Unable to find header") }
  }

  @Test
  fun `should start a new spa if MessageAttributes payload contains MessageAttributes`() {
    val message = GenericMessage<Any>("""{"MessageId":null,"MessageAttributes":{"my-event":{"Type": "String", "Value": "my-event"}}}""", mapOf("eventType" to "myevent"))

    val responseMessage = TraceExtractingMessageInterceptor(objectMapper).intercept(message)

    assertThat(responseMessage.headers).containsAllEntriesOf(message.headers)

    // this shows that we have created a new span
    assertThat(responseMessage.headers).containsKeys("span", "scope")

    // and no failure log messages
    assertThat(logger.formattedMessages()).isEmpty()
  }

  @Test
  fun `should do nothing if MessageAttributes payload is null`() {
    val message = GenericMessage<Any>("""{"MessageId":null,"MessageAttributes":null}""", mapOf("eventType" to "myevent"))

    val responseMessage = TraceExtractingMessageInterceptor(objectMapper).intercept(message)

    assertThat(responseMessage.headers).isEqualTo(message.headers)

    // no new span will be created, so don't expect these keys
    assertThat(responseMessage.headers).doesNotContainKeys("span", "scope")

    // and log message informing us that it couldn't find the header message attributes
    assertThat(logger.formattedMessages()).anyMatch { it.contains("Unable to find header") }
  }

  @Test
  fun `should do nothing if MessageAttributes payload is invalid json`() {
    val message = GenericMessage<Any>("""{invalid json "MessageAttributes":null""", mapOf("eventType" to "myevent"))

    val responseMessage = TraceExtractingMessageInterceptor(objectMapper).intercept(message)

    assertThat(responseMessage.headers).isEqualTo(message.headers)

    // no new span will be created, so don't expect these keys
    assertThat(responseMessage.headers).doesNotContainKeys("span", "scope")

    // but we do get a failure log message
    assertThat(logger.formattedMessages()).anyMatch { it.contains("Not attempting to extract trace context from message") }
  }

  @Test
  fun `should start a new spa if MessageAttributes payload is null but message attributes exist in header`() {
    val message = GenericMessage<Any>(
      """{"MessageId":null,"MessageAttributes":null}""",
      mapOf("eventType" to "myevent", SqsHeaders.SQS_SOURCE_DATA_HEADER to Message.builder().messageAttributes(eventTypeSqsMap("myevent")).build()),
    )

    val responseMessage = TraceExtractingMessageInterceptor(objectMapper).intercept(message)

    assertThat(responseMessage.headers).containsAllEntriesOf(message.headers)

    // this shows that we have created a new span
    assertThat(responseMessage.headers).containsKeys("span", "scope")

    // and no failure log messages
    assertThat(logger.formattedMessages()).isEmpty()
  }
}
