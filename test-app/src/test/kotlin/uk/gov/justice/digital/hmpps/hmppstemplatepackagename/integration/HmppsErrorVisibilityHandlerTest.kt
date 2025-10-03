package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atLeast
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.HmppsEvent
import uk.gov.justice.hmpps.sqs.countAllMessagesOnQueue
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import java.time.Duration
import java.time.temporal.ChronoUnit

@Import(SnsConfig::class)
@ActiveProfiles("visibility-test")
@ExtendWith(OutputCaptureExtension::class)
class HmppsErrorVisibilityHandlerTest : IntegrationTestBase() {
  @Test
  fun `event is retried from the queue retries rule`(output: CapturedOutput) = runTest {
    doThrow(RuntimeException("some error")).whenever(inboundMessageServiceSpy).handleMessage(any())

    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-DISCHARGE", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder()
        .topicArn(inboundTopicArn)
        .message(gsonString(event))
        .messageAttributes(
          mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
        )
        .build(),
    )

    await atLeast (Duration.of(3, ChronoUnit.SECONDS)) untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }
    assertThat(inboundSqsClient.countAllMessagesOnQueue(inboundQueueUrl).get()).isEqualTo(0)
    assertThat(output.out).contains("Setting error visibility timeout for event type OFFENDER_MOVEMENT-DISCHARGE on queue inboundqueue with receive count 1 to 1 seconds")
    assertThat(output.out).contains("Setting error visibility timeout for event type OFFENDER_MOVEMENT-DISCHARGE on queue inboundqueue with receive count 2 to 2 seconds")
    assertThat(output.out).contains("Setting error visibility timeout to 0 for event type OFFENDER_MOVEMENT-DISCHARGE on queue inboundqueue with receive count 3 because this is the last retry")
  }

  @Test
  fun `event is retried from the queue event retries rule`(output: CapturedOutput) = runTest {
    doThrow(RuntimeException("some error")).whenever(inboundMessageServiceSpy).handleMessage(any())

    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder()
        .topicArn(inboundTopicArn)
        .message(gsonString(event))
        .messageAttributes(
          mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
        )
        .build(),
    )

    await atLeast (Duration.of(1, ChronoUnit.SECONDS)) untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }
    assertThat(inboundSqsClient.countAllMessagesOnQueue(inboundQueueUrl).get()).isEqualTo(0)
    assertThat(output.out).contains("Setting error visibility timeout for event type OFFENDER_MOVEMENT-RECEPTION on queue inboundqueue with receive count 1 to 0 seconds")
    assertThat(output.out).contains("Setting error visibility timeout for event type OFFENDER_MOVEMENT-RECEPTION on queue inboundqueue with receive count 2 to 1 seconds")
    assertThat(output.out).contains("Setting error visibility timeout to 0 for event type OFFENDER_MOVEMENT-RECEPTION on queue inboundqueue with receive count 3 because this is the last retry")
  }

  @Test
  fun `event is retried from the queue event retries rule where event type contains periods`(output: CapturedOutput) = runTest {
    doThrow(RuntimeException("some error")).whenever(inboundMessageServiceSpy).handleMessage(any())

    val event = HmppsEvent("event-id", "test.type", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder()
        .topicArn(inboundTopicArn)
        .message(gsonString(event))
        .messageAttributes(
          mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
        )
        .build(),
    )

    await atLeast (Duration.of(1, ChronoUnit.SECONDS)) untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }
    assertThat(inboundSqsClient.countAllMessagesOnQueue(inboundQueueUrl).get()).isEqualTo(0)
    assertThat(output.out).contains("Setting error visibility timeout for event type test.type on queue inboundqueue with receive count 1 to 0 seconds")
    assertThat(output.out).contains("Setting error visibility timeout for event type test.type on queue inboundqueue with receive count 2 to 1 seconds")
    assertThat(output.out).contains("Setting error visibility timeout to 0 for event type test.type on queue inboundqueue with receive count 3 because this is the last retry")
  }
}
