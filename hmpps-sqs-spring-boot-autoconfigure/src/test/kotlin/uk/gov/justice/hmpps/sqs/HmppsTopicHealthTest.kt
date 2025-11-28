package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.health.contributor.Status
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.GetTopicAttributesRequest
import software.amazon.awssdk.services.sns.model.GetTopicAttributesResponse
import java.util.concurrent.CompletableFuture

class HmppsTopicHealthTest {

  private val topicId = "some-topic-id"
  private val topicArn = "some-topic-arn"
  private val snsClient = mock<SnsAsyncClient>()
  private val topicHealth = HmppsTopicHealth(HmppsTopic(topicId, topicArn, snsClient))

  @Test
  fun `should show status UP`() {
    mockHealthyTopic()

    val health = topicHealth.health()

    assertThat(health.status).isEqualTo(Status.UP)
  }

  @Test
  fun `should show topic arn`() {
    mockHealthyTopic()

    val health = topicHealth.health()

    assertThat(health.details["topicArn"]).isEqualTo("some-topic-arn")
  }

  @Test
  fun `should show interesting topic attributes`() {
    mockHealthyTopic()

    val health = topicHealth.health()

    assertThat(health.details["subscriptionsConfirmed"]).isEqualTo("1")
    assertThat(health.details["subscriptionsPending"]).isEqualTo("2")
  }

  @Test
  fun `should show status DOWN if cannot retrieve attributes`() {
    mockUnhealthyTopic()

    val health = topicHealth.health()

    assertThat(health.status).isEqualTo(Status.DOWN)
  }

  @Test
  fun `should show exception if cannot retrieve attributes`() {
    mockUnhealthyTopic()

    val health = topicHealth.health()

    assertThat(health.details["error"] as String).contains("Exception")
    assertThat(health.details["error"] as String).contains("some exception")
  }

  fun mockUnhealthyTopic() {
    whenever(snsClient.getTopicAttributes(any<GetTopicAttributesRequest>()))
      .thenThrow(RuntimeException("some exception"))
  }

  fun mockHealthyTopic() {
    whenever(snsClient.getTopicAttributes(any<GetTopicAttributesRequest>())).thenReturn(
      CompletableFuture.completedFuture(
        GetTopicAttributesResponse.builder()
          .attributes(mapOf("SubscriptionsConfirmed" to "1", "SubscriptionsPending" to "2"))
          .build(),
      ),
    )
  }
}
