package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sns.model.GetTopicAttributesResult
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.Health.Builder
import org.springframework.boot.actuate.health.HealthIndicator

class HmppsTopicHealth(private val hmppsTopic: HmppsTopic) : HealthIndicator {

  override fun health(): Health {
    val healthBuilder = Builder().up()

    healthBuilder.withDetail("topicArn", hmppsTopic.arn)

    getTopicAttributes()
      .onSuccess { result ->
        healthBuilder.withDetail("SubscriptionsConfirmed", """${result.attributes["SubscriptionsConfirmed"]}""")
        healthBuilder.withDetail("SubscriptionsPending", """${result.attributes["SubscriptionsPending"]}""")
      }
      .onFailure { throwable ->
        healthBuilder.down().withException(throwable)
      }

    return healthBuilder.build()
  }

  private fun getTopicAttributes(): Result<GetTopicAttributesResult> {
    return runCatching {
      hmppsTopic.snsClient.getTopicAttributes(hmppsTopic.arn)
    }
  }
}
