package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.actuate.health.Status
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException
import java.util.concurrent.CompletableFuture

class HmppsQueueHealthTest {

  private val sqsClient = mock<SqsAsyncClient>()
  private val sqsDlqClient = mock<SqsAsyncClient>()
  private val queueId = "some queue id"
  private val queueUrl = "some queue url"
  private val dlqUrl = "some dlq url"
  private val queueName = "some queue"
  private val dlqName = "some dlq"
  private val messagesOnQueueCount = 123
  private val messagesInFlightCount = 456
  private val messagesOnDLQCount = 789
  private val queueHealth = HmppsQueueHealth(HmppsQueue(queueId, sqsClient, queueName, sqsDlqClient, dlqName))

  @Test
  fun `should show status UP`() {
    mockHealthyQueue()

    val health = queueHealth.health()

    assertThat(health.status).isEqualTo(Status.UP)
  }

  @Test
  fun `should include queue name`() {
    mockHealthyQueue()

    val health = queueHealth.health()

    assertThat(health.details["queueName"]).isEqualTo(queueName)
  }

  @Test
  fun `should include interesting attributes`() {
    mockHealthyQueue()

    val health = queueHealth.health()

    assertThat(health.details["messagesOnQueue"]).isEqualTo("$messagesOnQueueCount")
    assertThat(health.details["messagesInFlight"]).isEqualTo("$messagesInFlightCount")
  }

  @Test
  fun `should show status DOWN`() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenThrow(QueueDoesNotExistException::class.java)

    val health = queueHealth.health()

    assertThat(health.status).isEqualTo(Status.DOWN)
  }

  @Test
  fun `should show exception causing status DOWN`() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenThrow(QueueDoesNotExistException::class.java)

    val health = queueHealth.health()

    assertThat(health.details["error"] as String).contains("Exception")
  }

  @Test
  fun `should show queue name if status DOWN`() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenThrow(QueueDoesNotExistException::class.java)

    val health = queueHealth.health()

    assertThat(health.details["queueName"]).isEqualTo(queueName)
  }

  @Test
  fun `should show status DOWN if unable to retrieve queue attributes`() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueUrlResponse()),
    )
    whenever(sqsClient.getQueueAttributes(someGetQueueAttributesRequest())).thenThrow(RuntimeException::class.java)

    val health = queueHealth.health()

    assertThat(health.status).isEqualTo(Status.DOWN)
  }

  @Test
  fun `should show DLQ status UP`() {
    mockHealthyQueue()

    val health = queueHealth.health()

    assertThat(health.details["dlqStatus"]).isEqualTo("UP")
  }

  @Test
  fun `should show DLQ name`() {
    mockHealthyQueue()

    val health = queueHealth.health()

    assertThat(health.details["dlqName"]).isEqualTo(dlqName)
  }

  @Test
  fun `should request queue url`() {
    mockHealthyQueue()

    queueHealth.health()

    verify(sqsClient).getQueueAttributes(
      check<GetQueueAttributesRequest> {
        assertThat(it.queueUrl()).isEqualTo(queueUrl)
      },
    )
  }

  @Test
  fun `should request DLQ url`() {
    mockHealthyQueue()

    queueHealth.health()

    verify(sqsDlqClient).getQueueAttributes(
      check<GetQueueAttributesRequest> {
        assertThat(it.queueUrl()).isEqualTo(dlqUrl)
      },
    )
  }

  @Test
  fun `should show interesting DLQ attributes`() {
    mockHealthyQueue()

    val health = queueHealth.health()

    assertThat(health.details["messagesOnDlq"]).isEqualTo("$messagesOnDLQCount")
  }

  @Test
  fun `should show status DOWN if DLQ status is down`() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueUrlResponse()),
    )
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueAttributesResponseWithoutDLQ()),
    )

    val health = queueHealth.health()

    assertThat(health.status).isEqualTo(Status.DOWN)
    assertThat(health.details["dlqStatus"]).isEqualTo("DOWN")
  }

  @Test
  fun `should show DLQ name if DLQ status is down`() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueUrlResponse()),
    )
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueAttributesResponseWithoutDLQ()),
    )

    val health = queueHealth.health()

    assertThat(health.details["dlqName"]).isEqualTo(dlqName)
  }

  @Test
  fun `should show DLQ status DOWN if no RedrivePolicy attribute on main queue`() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueUrlResponse()),
    )
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueAttributesResponseWithoutDLQ()),
    )

    val health = queueHealth.health()

    assertThat(health.details["dlqStatus"]).isEqualTo("DOWN")
  }

  @Test
  fun `should show DLQ status DOWN if DLQ not found`() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueUrlResponse()),
    )
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueAttributesResponseWithDLQ()),
    )
    whenever(sqsDlqClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build())).thenThrow(QueueDoesNotExistException::class.java)

    val health = queueHealth.health()

    assertThat(health.details["dlqStatus"]).isEqualTo("DOWN")
  }

  @Test
  fun `should show exception causing DLQ status DOWN`() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueUrlResponse()),
    )
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>())).thenReturn(
      CompletableFuture.completedFuture(someGetQueueAttributesResponseWithDLQ()),
    )
    whenever(sqsDlqClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build())).thenThrow(QueueDoesNotExistException::class.java)

    val health = queueHealth.health()

    assertThat(health.details["error"] as String).contains("Exception")
  }

  @Test
  fun `should show DLQ status DOWN if unable to retrieve DLQ attributes`() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>()))
      .thenReturn(CompletableFuture.completedFuture(someGetQueueUrlResponse()))
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
      .thenReturn(CompletableFuture.completedFuture(someGetQueueAttributesResponseWithDLQ()))
    whenever(sqsDlqClient.getQueueUrl(any<GetQueueUrlRequest>()))
      .thenReturn(CompletableFuture.completedFuture(someGetQueueUrlResponseForDLQ()))
    whenever(sqsDlqClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
      .thenThrow(RuntimeException::class.java)

    val health = queueHealth.health()

    assertThat(health.details["dlqStatus"]).isEqualTo("DOWN")
  }

  private fun mockHealthyQueue() {
    whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>()))
      .thenReturn(CompletableFuture.completedFuture(someGetQueueUrlResponse()))
    whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
      .thenReturn(CompletableFuture.completedFuture(someGetQueueAttributesResponseWithDLQ()))
    whenever(sqsDlqClient.getQueueUrl(any<GetQueueUrlRequest>()))
      .thenReturn(CompletableFuture.completedFuture(someGetQueueUrlResponseForDLQ()))
    whenever(sqsDlqClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
      .thenReturn(CompletableFuture.completedFuture(someGetQueueAttributesResponseForDLQ()))
  }

  private fun someGetQueueAttributesRequest() = GetQueueAttributesRequest.builder().queueUrl(queueUrl).attributeNames(listOf(QueueAttributeName.ALL)).build()

  private fun someGetQueueUrlResponse(): GetQueueUrlResponse = GetQueueUrlResponse.builder().queueUrl(queueUrl).build()
  private fun someGetQueueAttributesResponseWithoutDLQ() = GetQueueAttributesResponse.builder().attributes(
    mapOf(
      QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES to "$messagesOnQueueCount",
      QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE to "$messagesInFlightCount",
    ),
  ).build()

  private fun someGetQueueAttributesResponseWithDLQ() = GetQueueAttributesResponse.builder().attributes(
    mapOf(
      QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES to "$messagesOnQueueCount",
      QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE to "$messagesInFlightCount",
      QueueAttributeName.REDRIVE_POLICY to "any redrive policy",
    ),
  ).build()

  private fun someGetQueueUrlResponseForDLQ(): GetQueueUrlResponse = GetQueueUrlResponse.builder().queueUrl(dlqUrl).build()
  private fun someGetQueueAttributesResponseForDLQ() = GetQueueAttributesResponse.builder().attributes(
    mapOf(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES to messagesOnDLQCount.toString()),
  ).build()
}
