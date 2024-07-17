package uk.gov.justice.hmpps.sqs.telemetry

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import org.springframework.messaging.support.GenericMessage

@JsonTest
class TraceExtractingMessageInterceptorTest {

  @Autowired
  private lateinit var objectMapper: ObjectMapper

  @Test
  fun `should do nothing if no MessageAttributes header found`() {
    val message = GenericMessage<Any>("123", mapOf("eventType" to "myevent"))

    val responseMessage = assertDoesNotThrow {
      TraceExtractingMessageInterceptor(objectMapper).intercept(message)
    }

    assertThat(message.headers).isEqualTo(responseMessage.headers)
  }
}
