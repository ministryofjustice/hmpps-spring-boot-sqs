package uk.gov.justice.hmpps.sqs

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.Health.Builder
import org.springframework.boot.actuate.health.HealthIndicator
import software.amazon.awssdk.services.sns.model.GetTopicAttributesRequest
import software.amazon.awssdk.services.sns.model.GetTopicAttributesResponse

class HmppsTopicHealth(private val hmppsTopic: HmppsTopic) : HealthIndicator {

  override fun health(): Health {
    val healthBuilder = Builder().up()

    healthBuilder.withDetail("topicArn", hmppsTopic.arn)

    getTopicAttributes()
      .onSuccess { result ->
        healthBuilder.withDetail("subscriptionsConfirmed", """${result.attributes()["SubscriptionsConfirmed"]}""")
        healthBuilder.withDetail("subscriptionsPending", """${result.attributes()["SubscriptionsPending"]}""")
      }
      .onFailure { throwable ->
        healthBuilder.down().withException(throwable)
      }

    return healthBuilder.build()
  }

  private fun getTopicAttributes(): Result<GetTopicAttributesResponse> = runCatching {
    hmppsTopic.snsClient.getTopicAttributes(GetTopicAttributesRequest.builder().topicArn(hmppsTopic.arn).build()).get()
  }
}
