package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.util.concurrent.CompletableFuture

@ExtendWith(OutputCaptureExtension::class)
class HmppsQueueTest {
  private val sqsClient = mock<SqsAsyncClient>()
  private val sqsDlqClient = mock<SqsAsyncClient>()
  private val hmppsQueue = HmppsQueue("id", sqsClient, "queueName", sqsDlqClient, "dlqName")

  @BeforeEach
  fun setUp() {
    stubGetQueueUrl()
  }

  @Test
  fun `should get max receive count`() {
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
      .thenReturn(CompletableFuture.completedFuture(
        GetQueueAttributesResponse.builder()
          .attributes(
            mapOf(QueueAttributeName.REDRIVE_POLICY to "{\"deadLetterTargetArn\":\"arn:aws:sqs:eu-west-2:000000000000:dqlName\",\"maxReceiveCount\":4}")
          ).build()))

    assertThat(hmppsQueue.maxReceiveCount).isEqualTo(4)
  }

  @Test
  fun `should return null if no DLQ`() {
    val queue = HmppsQueue("id", sqsClient, "queueName")

    assertThat(queue.maxReceiveCount).isNull()
  }

  @Test
  fun `should return null if no redrive policy`() {
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
      .thenReturn(CompletableFuture.completedFuture(
        GetQueueAttributesResponse.builder()
          .attributes(mapOf())
          .build()))

    assertThat(hmppsQueue.maxReceiveCount).isNull()
  }

  @Test
  fun `should return null if there is an exception`() {
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
      .thenThrow(RuntimeException::class.java)

    assertThat(hmppsQueue.maxReceiveCount).isNull()
  }

  @Test
  fun `should log any exception`(output: CapturedOutput) {
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
      .thenThrow(RuntimeException::class.java)

    hmppsQueue.maxReceiveCount

    assertThat(output.toString()).contains("Unable to retrieve maxReceiveCount for queue queueName")
  }

  private fun stubGetQueueUrl() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>()))
      .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some-queue-url").build()))
  }
}